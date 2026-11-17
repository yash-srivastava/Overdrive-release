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
import com.overdrive.app.navmap.nav.TripStops
import com.overdrive.app.navmap.nav.CongestionSpan
import com.overdrive.app.navmap.nav.GeoPoint
import com.overdrive.app.navmap.nav.NavRouting
import com.overdrive.app.navmap.nav.RouteProvider
import com.overdrive.app.navmap.google.TrafficRefreshController
import com.overdrive.app.navmap.google.GoogleAttributionView
import com.overdrive.app.navmap.google.GoogleMapController
import com.overdrive.app.navmap.google.GoogleMapType
import com.overdrive.app.navmap.google.GoogleMapsBudget
import com.overdrive.app.navmap.google.GoogleMapsConfig
import com.overdrive.app.navmap.google.GoogleUsageReporter
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

    /** Google basemap/traffic layer; returns the vector style untouched when Google is unusable. */
    private lateinit var googleMap: GoogleMapController

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Background executor for the daemon HTTP calls — never on the looper. */
    @Volatile private var ioExecutor: ExecutorService? = null

    /** Monotonic token so a stale in-flight fetch result is discarded. */
    @Volatile private var fetchToken: Long = 0L

    /** Debounce runnable for camera-idle viewport refetches. */
    private val refetchRunnable = Runnable { fetchViewportHazards() }

    /** Most recent GPS fix (for the puck + prefetch); null until first fix. */
    @Volatile private var lastFix: RoadSenseHazardApiClient.LatLngFix? = null
        set(value) {
            field = value
            if (value != null) lastFixAtMono = value.measuredAtMono
            // a briefing trip that ran out of retries without a fix routes on the first fresh one (within 10 min)
            if (value != null && routeOnFreshFix && fixIsFresh(RESTORE_FIX_MAX_AGE_MS)) {
                routeOnFreshFix = false
                mainHandler.post {
                    if (isFinishing || isDestroyed || navigating || routeStops.isEmpty() || briefingStartAt <= 0L) return@post
                    if (android.os.SystemClock.elapsedRealtime() - briefingStartAt < FIX_WAIT_MAX_MS) {
                        recomputeItinerary(autoStart = true)
                    } else {
                        // as when retries run out: the stops stay to route by hand, nothing starts by itself
                        briefingStartAt = 0L
                        NavTripStore.clear(applicationContext)
                    }
                }
            }
        }
    /** When [lastFix] was measured (elapsedRealtime; 0 unknown), whichever source set it. */
    @Volatile private var lastFixAtMono = 0L
    /** The last routing attempt had no fix to start from; a briefing trip then waits for one. */
    private var lastRouteFailNoFix = false
    /** This briefing trip has had to wait for GPS at some point (it may since be routing on a late fix). */
    private var briefingWaitedForFix = false
    @Volatile private var routeOnFreshFix = false

    /** True until the first successful auto-recenter, so we only auto-jump once. */
    private var didInitialRecenter = false
    /** A recenter found no fix yet: the first fix the idle follow gets centres the map. */
    private var recenterPending = false
    /** The cluster camera has been put on a fix (until then it may sit far away, at the last-known spot). */
    private var clusterPlaced = false
    /** Style loads that failed in a row; retried a few times so a dropped network doesn't leave no map. */
    private var styleFailures = 0

    /**
     * Guard so the map click / camera-idle listeners are wired exactly once even
     * if onStyleLoaded runs again (e.g. a future day/night style reload). Defensive
     * — a duplicate registration would fire onMapTap / scheduleRefetch twice.
     */
    private var listenersWired = false

    // --- Navigation (route search + guidance) state ---
    // Route, alternates, congestion, turn arrow, hazards and pins; survives style reloads.
    private val glassLayers = com.overdrive.app.navmap.glass.MapGlassLayers()
    private val guidance = NavGuidanceEngine()
    private var navVoice: NavVoice? = null
    @Volatile private var navigating = false

    // --- Search autocomplete ---
    // Tap a result → route there; long-press → place sheet (save Home/Work/fav)
    // so saving never requires computing a route first.
    private val searchAdapter = NavSearchResultAdapter(
        onResultTap = { result -> onSearchResultChosen(result) },
        onResultLongPress = { result ->
            if (result.needsResolve) resolvePlaceThen(result) { showPlaceSheet(it, isDroppedPin = false) }
            else showPlaceSheet(result, isDroppedPin = false)
        },
        onResultRemove = { result -> removeRecentSearch(result) }
    )
    @Volatile private var acToken: Long = 0L
    private var pendingAutocomplete: Runnable? = null

    // --- Alternate-route preview (before guidance starts) ---
    private val routeOptionAdapter = NavRouteOptionAdapter { idx -> onRouteOptionSelected(idx) }
    private var previewRoutes: List<NavRoute> = emptyList()
    private var previewDestLabel: String = ""

    // --- Live traffic on the route (Google routes only; inert for Valhalla) ---
    // What the congestion overlay shows.
    private var displayedTrafficRoute: NavRoute? = null
    private var displayedTrafficSpans: List<CongestionSpan> = emptyList()
    // The car's along-route distance from the frame loop.
    @Volatile private var trafficTraveledM = 0.0
    // Head unit only; non-null while a Google route is being guided.
    private var trafficRefresh: TrafficRefreshController? = null
    // Traffic-aware remaining ETA for trafficEtaRoute, stamped with elapsedRealtime.
    private var trafficEtaS = Double.NaN
    private var trafficEtaAtMs = 0L
    private var trafficEtaRoute: NavRoute? = null
    private var clusterTrafficListener: NavSession.TrafficListener? = null
    private var lastClusterTraffic: NavSession.Traffic? = null
    private var fasterRouteBar: Snackbar? = null
    private val dismissFasterRoute = Runnable { dismissFasterRoutePrompt() }
    // Latest place-resolve request; an older in-flight lookup is discarded.
    private var placeResolveToken = 0L

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
    /** The map is behind another app (or the screen is off): guidance goes on, drawing doesn't. */
    @Volatile private var mapStopped = false
    /** Bumped when guidance starts or stops, so a reroute still in flight from before is dropped. */
    private var rerouteGen = 0
    /** The trip's stops changed while a reroute was in flight: plan again when it lands. */
    private var rerouteAgain = false
    private var lastRerouteMs: Long = 0L
    private var previewSelectedIdx: Int = 0
    private var routeSheetBehavior:
        com.google.android.material.bottomsheet.BottomSheetBehavior<View>? = null

    // --- POI along route (EV charging / fuel) ---
    private var poiSource: GeoJsonSource? = null
    @Volatile private var poiEnabled = false

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
    /** 0 = hidden, 1 = severe only, 2 = moderate+ (≥2), 3 = all (default). */
    private var hazardFilterMode = HAZARD_FILTER_ALL

    // --- 2D / 3D buildings ---
    /** Persisted 2D/3D choice; default 3D (vector basemap only). The low glass tier draws the extrusions opaque,
     *  which keeps them affordable on the Adreno 610. */
    private var map3dEnabled = true

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

    /** A restored trip's routing retries while GPS or data comes up after car start. */
    private var routeGen = 0
    private var routeRetries = 0
    private var autoRouting = false
    /** When a Trip Briefing yes asked to start guidance (elapsedRealtime), while it hasn't started; else 0. */
    private var briefingStartAt = 0L
    /** The map was left while that trip was routing: it carries on when the map is back soon. */
    private var routeOnReturn = false
    /** The trip left behind was waiting for GPS: on return it may keep waiting up to [FIX_WAIT_MAX_MS]. */
    private var fixWaitOnReturn = false
    private val routeRetry = Runnable {
        if (!navigating && routeStops.isNotEmpty()) recomputeItinerary(autoStart = true, retry = true)
    }

    /**
     * True when this Activity instance was launched onto the driver cluster
     * (am start --display N --ez cluster true). The cluster is a NON-TOUCH display,
     * so in this mode we strip every touch control, force a larger heading-up
     * immersive camera, and keep the view glanceable. Set once in onCreate.
     */
    private var clusterMode = false
    private var layoutGeneration = 0L
    private var layoutDisplayId = -1
    private val layoutCheckPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val projectionCheckPending = java.util.concurrent.atomic.AtomicBoolean(false)


    // ── Motion smoothing (dead-reckoning) ───────────────────────────────────────
    // GPS fixes (direct listener, daemon poll, guidance tick) feed the estimator; a vsync
    // frame loop shows the puck + camera from it (motion/NavMotionModel, NavCameraRig).
    private val motionEstimator = com.overdrive.app.navmap.nav.VehicleMotionEstimator()
    /** True while the frame loop should run (nav, idle puck glide, or cluster follow). */
    @Volatile private var motionFollowActive = false
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

    private val motionModel = com.overdrive.app.navmap.motion.NavMotionModel()
    private val cameraRig = com.overdrive.app.navmap.motion.NavCameraRig()
    private var motionTrack: com.overdrive.app.navmap.motion.RouteTrack? = null
    private var hud: com.overdrive.app.navmap.ui.NavHudController? = null
    private var hudShown = false
    /** Maneuver on the HUD card and its along-route position, for the live distance. */
    private var hudManeuverRef: com.overdrive.app.navmap.nav.RouteManeuver? = null
    private var hudManeuverS = Double.NaN
    private var hudTickMs = 0L
    /** Sole writer of the puck, and of the camera while navigating or on the cluster. */
    private val frameLoop by lazy {
        com.overdrive.app.navmap.motion.NavFrameLoop(
            allowFull = !clusterMode && com.overdrive.app.navmap.motion.NavFrameLoop.fullRateCapable()
        ) { now -> renderMotionFrame(now) }
    }
    private var frameCapFps = 0
    private val perfMeter = com.overdrive.app.navmap.motion.NavPerfMeter()
    /** Lighter overlay set and solid buildings below 8155-class SoCs and on the cluster. */
    private val glassLowTier by lazy { clusterMode || !com.overdrive.app.navmap.motion.NavFrameLoop.fullRateCapable() }
    private val routeSnap: (Double, Double, com.overdrive.app.navmap.motion.NavMotionModel.Snap) -> Boolean =
        { la, lo, out ->
            val s = guidance.snapToRoute(la, lo)
            if (s == null) false else {
                out.alongM = s.alongRouteM
                out.offsetM = s.offsetM
                true
            }
        }
    private val recenterAfterFree = Runnable {
        if ((navigating || (clusterMode && layoutGeneration > 0)) &&
            cameraRig.mode == com.overdrive.app.navmap.motion.NavCameraRig.Mode.FREE) {
            recenterFollow(android.os.SystemClock.elapsedRealtime())
        }
    }

    private fun startMotionFollow() {
        // (nothing to draw behind another app: onResume starts it again)
        if (mapStopped) return
        motionFollowActive = true
        if (!frameLoop.isRunning) frameLoop.start()
    }

    private fun stopMotionFollow() {
        motionFollowActive = false
        frameLoop.stop()
        capRenderRate(0)
    }

    /** Renderer cap: a third above the loop's rate while it runs, so the cap never delays a swap; never below the idle cap. */
    private fun capRenderRate(loopFps: Int) {
        val idle = if (clusterMode) CLUSTER_MAX_FPS else HEADUNIT_MAX_FPS
        val fps = maxOf(idle, loopFps * 4 / 3)
        if (fps == frameCapFps || !::mapView.isInitialized) return
        frameCapFps = fps
        mapView.setMaximumFps(fps)
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
        com.overdrive.app.navmap.nav.VectorTileKeys.attach(applicationContext)
        com.overdrive.app.navmap.nav.MapNetworking.installMapLibreHttpClient()

        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_roadsense_map)
        // The first-focus holder only exists for touch mode; key navigation must never land on it.
        findViewById<View>(R.id.mapFocusSink)?.let { sink ->
            val track = { inTouch: Boolean ->
                sink.isFocusableInTouchMode = inTouch
                if (!inTouch) sink.isFocusable = false
            }
            track(sink.isInTouchMode)
            // Deferred: the key that leaves touch mode first moves focus off the sink as a normal focus step.
            sink.viewTreeObserver.addOnTouchModeChangeListener { inTouch -> sink.post { track(inTouch) } }
        }

        // Cluster mode: launched onto the non-touch driver-cluster display by the
        // daemon. Detected via the launching COMPONENT (the singleInstance alias
        // RoadSenseClusterMapActivity) OR the legacy `cluster` extra — component is
        // the robust signal (survives onNewIntent / can't be lost). Strip touch
        // chrome + go glanceable.
        // Cluster mode when we're the dedicated cluster subclass (robust — survives
        // onNewIntent / can't be lost) OR the legacy `cluster` extra is set.
        clusterMode = this is RoadSenseClusterMapActivity || this is RoadSenseLayoutMapActivity ||
            intent?.getBooleanExtra("cluster", false) == true
        layoutGeneration = if (clusterMode) intent.getLongExtra("layoutGeneration", 0L) else 0L
        layoutDisplayId = if (layoutGeneration > 0) intent.getIntExtra("layoutDisplayId", -1) else -1

        // Safety: the cluster instance must NEVER end up on the default (head-unit)
        // display — if AMS placed it on display 0 (no cluster display present), it
        // would clobber the infotainment map. Finish immediately so the touch
        // instance is untouched.
        if (clusterMode && currentDisplayId() == android.view.Display.DEFAULT_DISPLAY) {
            android.util.Log.w("RoadSenseMap", "cluster instance landed on display 0 — finishing")
            finishAndRemoveTask()
            return
        }
        // Before the first style build: the controller decides Google vs vector in styleBuilderForTheme().
        // On failure googleMap stays uninitialized and every call site falls back to the vector map.
        try {
            GoogleMapsBudget.init(applicationContext)
            GoogleUsageReporter.restoreSoon()
            googleMap = GoogleMapController(this, clusterMode && layoutGeneration <= 0, googleHost)
        } catch (t: Throwable) {
            Log.w("RoadSenseMap", "google map controller unavailable", t)
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
        setupNavHud()

        // Responsive: on a wide (landscape) screen, cap the floating panels to a
        // column + center them (full-width edge-to-edge reads stretched on the
        // 1920px Seal). Re-applied on rotation via onConfigurationChanged.
        applyResponsiveLayout()

        findViewById<FloatingActionButton>(R.id.fabLocate).setOnClickListener {
            if (navigating && !clusterMode &&
                cameraRig.mode != com.overdrive.app.navmap.motion.NavCameraRig.Mode.FOLLOW
            ) recenterFollow(android.os.SystemClock.elapsedRealtime()) else recenter()
        }

        // Destination search — Gmap-style: type-ahead autocomplete (debounced
        // Photon) drives the dropdown; submit (IME / glyph) is the on-submit
        // fallback. Wired in setupSearch().
        setupSearch()
        NavRouting.arrivalInstruction = getString(R.string.navmap_google_arrived)
        findViewById<FloatingActionButton>(R.id.fabEndNav)?.setOnClickListener {
            endTrip()
            showSnackbar(getString(R.string.roadsense_map_nav_ended))
        }

        // Hazard visibility filter (toggle / severity) + POI (EV charging / fuel) toggle.
        setupHazardFilter()
        findViewById<FloatingActionButton>(R.id.fabPoi)?.setOnClickListener { togglePoi() }
        findViewById<FloatingActionButton>(R.id.fabMap3d)?.setOnClickListener { toggleMap3d() }
        findViewById<FloatingActionButton>(R.id.fabMapLayers)?.setOnClickListener { showMapLayersMenu(it) }

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
        installGlSurfaceRebuild()
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
            findViewById<View>(R.id.navHud)?.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            // The search COLUMN (bar + dropdown) sits level with the back pill. Offset
            // the whole column (NOT the inner card — that double-offset was the misalignment).
            (searchColumn?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = bars.top + dp(16); searchColumn.layoutParams = it
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
            // Top-end control capsule, level with the back pill.
            (controlsTop?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = bars.top + dp(16); controlsTop.layoutParams = it
            }
            // Immersive back FAB: just under the status bar, top-start.
            (fabNavBack?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = bars.top + dp(16); fabNavBack.layoutParams = it
            }
            (fabLocate?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = bars.bottom + dp(16); fabLocate.layoutParams = it
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
            // Zoom capsule: 10dp above the 56dp recenter button.
            (zoomControls?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = bars.bottom + dp(82); zoomControls.layoutParams = it
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
     * Cap the maneuver banner to a column width and center it on wide/landscape
     * screens (portrait/narrow: full width), and size the start-aligned search pill
     * between the back pill and the end control capsule. The route sheet stays full-width.
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
        // Search pill: start-aligned after the back pill, stopping short of the end capsule.
        findViewById<View>(R.id.navSearchColumn)?.let { v ->
            (v.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let { lp ->
                val inset = resources.getDimensionPixelSize(R.dimen.map_search_inset)
                lp.width = minOf(resources.getDimensionPixelSize(R.dimen.map_search_max_width),
                    (screenW - 2 * inset).coerceAtLeast(0))
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                v.layoutParams = lp
            }
        }
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
                        // a held fix may be from before a tow: only a fresh one counts as placed
                        if (!clusterPlaced) {
                            placeClusterCamera(fix)
                            clusterPlaced = fixIsFresh(CLUSTER_FIX_FRESH_MS)
                        }
                        snapIfFar(fix)
                        feedMotionTruth(fix)   // single source of truth → render tick paints
                        startMotionFollow()    // idempotent; 30 fps glide of puck + camera
                    }
                }
            }
            mainHandler.postDelayed(this, GUIDANCE_TICK_MS)
        }
    }

    // A far first fix (no last-known spot, or the car moved while the map was off): jump there, don't glide.
    private fun placeClusterCamera(fix: RoadSenseHazardApiClient.LatLngFix) {
        if (layoutGeneration > 0 &&
            cameraRig.mode == com.overdrive.app.navmap.motion.NavCameraRig.Mode.FREE) return
        val m = map ?: return
        val t = m.cameraPosition.target
        val far = t == null || guidance.haversineMeters(t.latitude, t.longitude, fix.lat, fix.lng) > CLUSTER_PLACE_FAR_M
        if (far) m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lng), FOLLOW_ZOOM))
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

    /**
     * The head unit can hand MapLibre a new surface before its buffer size lands (Adreno "Invalid width or height:
     * W = 0, H = 0"), leaving depth/stencil unusable: the clear colour and puck draw, every tile stays blank until a
     * resize. Re-sending the size shortly after makes MapLibre rebuild its EGL surface, as the old auto-keyboard did.
     */
    private var glSurface: android.view.SurfaceView? = null
    private var glSurfaceFormat = android.graphics.PixelFormat.TRANSPARENT
    private val glSurfaceRebuild = Runnable {
        val sv = glSurface ?: return@Runnable
        if (isFinishing || isDestroyed || mapStopped) return@Runnable
        val frame = sv.holder.surfaceFrame
        if (sv.holder.surface?.isValid != true || frame.width() <= 0 || frame.height() <= 0) return@Runnable
        try {
            (sv as android.view.SurfaceHolder.Callback).surfaceChanged(sv.holder, glSurfaceFormat, frame.width(), frame.height())
            Log.d("RoadSenseMap", "GL surface rebuilt ${frame.width()}x${frame.height()}")
        } catch (t: Throwable) {
            Log.w("RoadSenseMap", "GL surface rebuild failed: ${t.javaClass.simpleName}")
        }
    }

    private fun installGlSurfaceRebuild() {
        if (clusterMode) return
        val sv = (0 until mapView.childCount).map { mapView.getChildAt(it) }
            .firstOrNull { it is android.view.SurfaceView && it is android.view.SurfaceHolder.Callback } as? android.view.SurfaceView
            ?: return
        glSurface = sv
        // Registered after MapLibre's own callback; calling its surfaceChanged directly doesn't re-enter this one.
        sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {}
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                glSurfaceFormat = format
                mainHandler.removeCallbacks(glSurfaceRebuild)
                mainHandler.postDelayed(glSurfaceRebuild, GL_SURFACE_REBUILD_MS)
            }
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                mainHandler.removeCallbacks(glSurfaceRebuild)
            }
        })
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
     * <p>It refreshes the puck and keeps the car in view ([followIdleCamera]) until the
     * driver pans or zooms the map; from then the view stays theirs, the blue dot just
     * truthful underneath it, until Locate (or a trip's end) gives the follow back.
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
                    val wasPending = recenterPending
                    recenterIfPending(fix.lat, fix.lng)
                    snapIfFar(fix)
                    // SOTA idle glide: feed the estimator + run the render tick so the
                    // idle puck dead-reckons + gyro-fuses between the sparse 1 Hz fixes
                    // (was: updateLocationPuck = raw teleport once/sec). renderMotionFrame
                    // leaves the camera alone when idle; only the follow below moves it.
                    feedMotionTruth(fix)
                    startMotionFollow()   // idempotent; 30/200ms adaptive tick
                    if (idleFollowCamera && !wasPending && !idleFollowHeld()) followIdleCamera(fix.lat, fix.lng)
                }
            }
            mainHandler.postDelayed(this, GUIDANCE_TICK_MS)
        }
    }

    /** Idle (no trip), the map keeps the car in view until the driver pans it; Locate gives the follow back. */
    private var idleFollowCamera = true

    // A plan or a place on screen (a route preview, its locked destination, a place sheet): the driver's looking at it.
    // Stops alone don't hold it: a search whose routing failed leaves them with nothing drawn.
    private fun idleFollowHeld(): Boolean =
        previewRoutes.isNotEmpty() || !lockedDestLat.isNaN() || openPlaceSheet != null

    /** When the camera's current move began (a zoom, a recentre, a fling); 0 once it's idle. */
    private var cameraBusySince = 0L

    // The car near the edge of the middle half of the screen: an ease back to it (zoom, tilt and bearing kept).
    private fun followIdleCamera(fixLat: Double, fixLng: Double) {
        val m = map ?: return
        if (!::mapView.isInitialized || mapView.width <= 0 || mapView.height <= 0) return
        val busy = cameraBusySince
        if (busy > 0L && android.os.SystemClock.elapsedRealtime() - busy < CAMERA_BUSY_MAX_MS) return
        // the puck as drawn, not the raw fix: one bad fix doesn't take the view off the car
        val drawn = motionFollowActive && !(motionModel.lat == 0.0 && motionModel.lng == 0.0)
        val lat = if (drawn) motionModel.lat else fixLat
        val lng = if (drawn) motionModel.lng else fixLng
        val p = m.projection.toScreenLocation(LatLng(lat, lng))
        val w = mapView.width.toFloat()
        val h = mapView.height.toFloat()
        if (p.x in w * 0.25f..w * 0.75f && p.y in h * 0.25f..h * 0.75f) return
        val t = m.cameraPosition.target
        val far = t == null || guidance.haversineMeters(t.latitude, t.longitude, lat, lng) > CLUSTER_PLACE_FAR_M
        val to = CameraUpdateFactory.newLatLng(LatLng(lat, lng))
        if (far) m.moveCamera(to) else m.animateCamera(to, RECENTER_ANIM_MS)
    }

    /** The map opened (or Locate was tapped) before there was a fix: the first one centres it (head unit, idle). */
    private fun recenterIfPending(lat: Double, lng: Double) {
        if (!recenterPending || navigating || clusterMode) return
        recenterPending = false
        centreOn(lat, lng)
    }

    // Street zoom with the resting tilt; a jump rather than a glide across a long way (a stale saved spot).
    private fun centreOn(lat: Double, lng: Double) {
        val m = map ?: return
        val pos = org.maplibre.android.camera.CameraPosition.Builder()
            .target(LatLng(lat, lng)).zoom(FOLLOW_ZOOM).tilt(restingTilt()).bearing(0.0).build()
        val t = m.cameraPosition.target
        val far = t == null || guidance.haversineMeters(t.latitude, t.longitude, lat, lng) > CLUSTER_PLACE_FAR_M
        if (far) m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
        else m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), RECENTER_ANIM_MS)
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
            if (layoutGeneration > 0) {
                val expectedGeneration = layoutGeneration
                val expectedDisplay = layoutDisplayId
                val actualDisplay = currentDisplayId()
                if (layoutCheckPending.compareAndSet(false, true)) {
                    try {
                        ioExecutor().execute {
                            val alive = com.overdrive.app.navmap.cluster.LayoutMapSessionClient.alive(
                                expectedGeneration, expectedDisplay, actualDisplay)
                            mainHandler.post {
                                layoutCheckPending.set(false)
                                if (layoutGeneration == expectedGeneration && alive == false &&
                                    !isFinishing && !isDestroyed) {
                                    clusterFinishing = true
                                    mainHandler.removeCallbacks(clusterFollowRunnable)
                                    finishAndRemoveTask()
                                }
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        layoutCheckPending.set(false)
                    }
                }
                mainHandler.postDelayed(this, CLUSTER_FINISH_POLL_MS)
                return
            }
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
            if (projectionCheckPending.compareAndSet(false, true)) {
                try {
                    ioExecutor().execute {
                        val active = isClusterProjectionActive()
                        mainHandler.post {
                            projectionCheckPending.set(false)
                            if (isFinishing || isDestroyed || clusterMode) return@post
                            if (active != projectionActive) {
                                projectionActive = active
                                // Re-kick the render tick so the rate change takes effect now.
                                if (motionFollowActive) startMotionFollow()
                            }
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    projectionCheckPending.set(false)
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
            (nav != null && nav.optBoolean("clusterMapActive", false)) ||
                (com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("projection")?.optBoolean("clusterLayoutMapActive", false) == true &&
                    com.overdrive.app.navmap.cluster.LayoutMapSessionClient.mapProjected())
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
                lp.width = if (layoutGeneration > 0)
                    minOf(px(CLUSTER_BANNER_WIDTH_DP), maxOf(64, root.width - stackLeftX * 2))
                    else px(CLUSTER_BANNER_WIDTH_DP)
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
        // The HUD's compact cluster stack takes the banner's band beside the speed badge.
        findViewById<com.overdrive.app.navmap.ui.NavHudLayout>(R.id.navHud)?.clusterBounds = android.graphics.Rect(
            CLUSTER_SPEED_BADGE_LEFT_PX, panelH / 2,
            CLUSTER_SPEED_BADGE_LEFT_PX + (if (layoutGeneration > 0)
                minOf(px(CLUSTER_BANNER_WIDTH_DP), maxOf(64, root.width - CLUSTER_SPEED_BADGE_LEFT_PX * 2))
                else px(CLUSTER_BANNER_WIDTH_DP)), panelH - px(8)
        )
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
        capRenderRate(0)
        // A style that never loads (no network for its sources) must not leave the reload guard stuck, nor no map.
        mapView.addOnDidFailLoadingMapListener { message ->
            styleReloadInFlight = false
            Log.w("RoadSenseMap", "map style failed to load: $message")
            if (styleFailures++ < STYLE_RETRY_MAX) mainHandler.postDelayed({
                if (map?.style?.isFullyLoaded != true) requestGoogleStyleReload()
            }, STYLE_RETRY_MS)
        }
        // Cluster counts and stop numbers are drawn the first time the style asks for them.
        mapView.addOnStyleImageMissingListener { id ->
            map?.style?.let { st -> com.overdrive.app.navmap.glass.MapGlassArt.drawMissing(this, st, id, isNightTheme()) }
        }
        mapView.addOnDidFinishRenderingFrameListener(
            org.maplibre.android.maps.MapView.OnDidFinishRenderingFrameWithStatsListener { _, stats ->
                frameLoop.onFrameRendered()
                if (stats != null) perfMeter.onFrame(stats.encodingTime, stats.renderingTime)
            }
        )
        mlMap.addOnCameraMoveStartedListener { reason ->
            // the driver's (or the app's own) newer view wins over a recenter still waiting for a fix
            recenterPending = false
            cameraBusySince = android.os.SystemClock.elapsedRealtime()
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserMovedMap()
        }
        mlMap.addOnCameraIdleListener { cameraBusySince = 0L }

        // App-like feel: lock rotation + tilt, hide the compass (irrelevant
        // when north-locked) but ENABLE on-screen zoom +/- controls (no
        // pinch-only). Keep attribution/logo (OSM data license requires it).
        mlMap.uiSettings.apply {
            setRotateGesturesEnabled(false)
            setTiltGesturesEnabled(false)
            setCompassEnabled(false)
            // Cluster is non-touch → kill ALL gestures; head-unit keeps pinch/zoom.
            setZoomGesturesEnabled(!clusterMode || layoutGeneration > 0)
            setQuickZoomGesturesEnabled(!clusterMode || layoutGeneration > 0)
            setScrollGesturesEnabled(!clusterMode || layoutGeneration > 0)
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

        // Initial camera: where the car last was (its tiles are cached), else the default region, until the
        // first fix / recenter narrows it.
        mlMap.moveCamera(initialCamera())

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
        applyAttributionChrome()
    }

    /**
     * MapLibre's logo and attribution sit where the HUD's speed orb goes, so they hide while the HUD shows (its plate
     * prints the attribution). The Google logo + copyright takes the MapLibre logo's slot, or the plate's slot
     * bottom-right under the HUD. Cluster: flush with the speed badge's left edge, same inset from the bottom.
     */
    private fun applyAttributionChrome() {
        val google = googleStyleActive()
        if (!clusterMode) map?.uiSettings?.let { ui ->
            ui.setLogoEnabled(!google && !hudShown)
            ui.setAttributionEnabled(!hudShown)
        }
        val v = findViewById<View>(R.id.googleAttribution) ?: return
        val lp = v.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams ?: return
        val d = resources.displayMetrics.density
        val navBottom = rootBottomInsetPx()
        when {
            clusterMode -> {
                lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                lp.marginStart = CLUSTER_SPEED_BADGE_LEFT_PX; lp.marginEnd = 0
                lp.bottomMargin = CLUSTER_SPEED_BADGE_LEFT_PX
            }
            hudShown -> {
                lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                lp.marginStart = 0; lp.marginEnd = (10 * d).toInt()
                lp.bottomMargin = navBottom + (5 * d).toInt()
            }
            else -> {
                lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                lp.marginStart = (40 * d).toInt(); lp.marginEnd = 0
                lp.bottomMargin = navBottom + (8 * d).toInt()
            }
        }
        v.layoutParams = lp
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

    /** OpenFreeMap liberty for both themes; MapGlassBasemap recolours it at night. */
    private fun styleUrlForTheme(): String = STYLE_URL_LIGHT

    /** Bundled liberty style asset (both themes). */
    private fun styleAssetForTheme(): String = STYLE_ASSET_LIGHT

    /** Basemap for the current theme: Google raster when the controller allows it, else the vector style. */
    private fun styleBuilderForTheme(): Style.Builder {
        if (!::googleMap.isInitialized) return vectorStyleBuilder()
        return try {
            googleMap.buildStyle { vectorStyleBuilder() }
        } catch (t: Throwable) {
            Log.w("RoadSenseMap", "google style build failed, using the vector basemap", t)
            vectorStyleBuilder()
        }
    }

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
    private fun vectorStyleBuilder(): Style.Builder {
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
        // A Dark map theme can be picked while the app runs in day mode, where _dark roles don't resolve.
        if (isNightTheme()) return m3Night(role)
        val id = resources.getIdentifier("md_sys_color_${role}_light", "color", packageName)
        return if (id != 0) ContextCompat.getColor(this, id) else 0xFF808080.toInt()
    }

    // The _dark roles live only in values-night, so they resolve through a night configuration.
    private val nightColorContext: android.content.Context by lazy {
        val cfg = android.content.res.Configuration(resources.configuration)
        cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        createConfigurationContext(cfg)
    }

    /** Dark M3 role colour regardless of the current theme. */
    private fun m3Night(role: String): Int {
        val id = resources.getIdentifier("md_sys_color_${role}_dark", "color", packageName)
        if (id == 0) return 0xFF808080.toInt()
        return try { ContextCompat.getColor(nightColorContext, id) } catch (_: Throwable) { 0xFF808080.toInt() }
    }

    /** Hex string form of [m3], for paint properties set via a color string. */
    private fun m3Hex(role: String): String = colorToHex(m3(role))

    /**
     * CSS colour for MapLibre: "#RRGGBB", or rgba() when translucent. "#AARRGGBB" is not CSS: MapLibre rejected it
     * ("value must be a valid color"), dropping the Google style's background layer, so the map showed bare black.
     */
    private fun colorToHex(color: Int): String {
        val a = color ushr 24
        return if (a == 0xFF) String.format(java.util.Locale.ROOT, "#%06X", color and 0xFFFFFF)
        else String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
            (color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, a / 255f)
    }

    private fun fixIsFresh(maxAgeMs: Long): Boolean =
        lastFixAtMono > 0L && android.os.SystemClock.elapsedRealtime() - lastFixAtMono in 0L..maxAgeMs

    // A fix far from the shown pose (towed, ferried, or a held fix finally replaced): seed there, don't crawl.
    private fun snapIfFar(fix: RoadSenseHazardApiClient.LatLngFix) {
        val lat = motionModel.lat
        val lng = motionModel.lng
        val far = !(lat == 0.0 && lng == 0.0) && guidance.haversineMeters(lat, lng, fix.lat, fix.lng) > SNAP_FAR_M
        val good = (fix.accuracy ?: Double.MAX_VALUE) <= SNAP_MAX_ACCURACY_M && fix.measuredAtMono > 0L &&
            android.os.SystemClock.elapsedRealtime() - fix.measuredAtMono in 0L..CLUSTER_FIX_FRESH_MS
        if (!far || !good) {
            snapCandidate = null
            return
        }
        val c = snapCandidate
        val apart = if (c == null) -1L else fix.measuredAtMono - c.measuredAtMono
        // the same fix read again (the daemon repeats its last one until a new one) doesn't confirm itself
        if (c != null && (apart in 0L until SNAP_CONFIRM_GAP_MS || (c.lat == fix.lat && c.lng == fix.lng))) return
        if (c == null || apart !in 0L..SNAP_CONFIRM_WINDOW_MS ||
            guidance.haversineMeters(c.lat, c.lng, fix.lat, fix.lng) > SNAP_CONFIRM_M
        ) {
            snapCandidate = fix
            return
        }
        snapCandidate = null
        motionEstimator.reset()
        motionModel.reset()
    }

    /** A far fix waiting for a second, nearby one before the shown pose jumps there. */
    private var snapCandidate: RoadSenseHazardApiClient.LatLngFix? = null

    // The last spot saved by [saveLastCamera]; the default region the first time.
    private fun initialCamera(): org.maplibre.android.camera.CameraUpdate {
        val p = getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE)
        val lat = p.getFloat(KEY_LAST_CAM_LAT, Float.NaN).toDouble()
        val lng = p.getFloat(KEY_LAST_CAM_LNG, Float.NaN).toDouble()
        val zoom = p.getFloat(KEY_LAST_CAM_ZOOM, FOLLOW_ZOOM.toFloat()).toDouble()
        val known = lat.isFinite() && lng.isFinite() && kotlin.math.abs(lat) <= 90.0 && kotlin.math.abs(lng) <= 180.0
        if (known) return CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom.coerceIn(DEFAULT_ZOOM, FOLLOW_ZOOM))
        // never opened before: the system's last known location beats a default region far away
        val last = lastKnownLocation()
        return if (last != null) {
            CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), DEFAULT_ZOOM)
        } else {
            CameraUpdateFactory.newLatLngZoom(LatLng(DEFAULT_LAT, DEFAULT_LNG), DEFAULT_ZOOM)
        }
    }

    private fun lastKnownLocation(): android.location.Location? = try {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter {
                kotlin.math.abs(it.latitude) <= 90.0 && kotlin.math.abs(it.longitude) <= 180.0 &&
                    !(kotlin.math.abs(it.latitude) < 1e-4 && kotlin.math.abs(it.longitude) < 1e-4) && !it.isFromMockProvider
            }
            .maxByOrNull { it.time }
    } catch (t: Throwable) {
        null
    }

    /** The car's last fix (else the camera, unless still on the default region), for the next open's first frame. */
    private fun saveLastCamera() {
        val cp = map?.cameraPosition ?: return
        val fix = lastFix
        val lat = fix?.lat ?: cp.target?.latitude ?: return
        val lng = fix?.lng ?: cp.target?.longitude ?: return
        if (!lat.isFinite() || !lng.isFinite() || (fix == null && lat == DEFAULT_LAT && lng == DEFAULT_LNG)) return
        getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE).edit()
            .putFloat(KEY_LAST_CAM_LAT, lat.toFloat())
            .putFloat(KEY_LAST_CAM_LNG, lng.toFloat())
            .putFloat(KEY_LAST_CAM_ZOOM, cp.zoom.toFloat())
            .apply()
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
        styleFailures = 0
        try {
            com.overdrive.app.navmap.glass.MapGlassArt.registerStatic(this, style, isNightTheme())
            hud?.setNight(isNightTheme())
            hud?.setAttributionVisible(!googleStyleActive())
        // Google raster + traffic go in first so every roadsense-* layer stacks above them.
        // Isolated so a Google failure can never skip the overlay setup below.
        try {
            if (::googleMap.isInitialized) map?.let { googleMap.onStyleLoaded(style, it) }
            bindGoogleAttribution()
            updateMapLayersFab()
        } catch (t: Throwable) {
            Log.w("RoadSenseMap", "google style setup failed", t)
        }
        // Record the scheme this style was loaded for, so onConfigurationChanged can tell
        // a real day/night flip (→ reload) from a pure rotation/density delta (→ skip).
        lastAppliedNight = isNightTheme()
        val night = isNightTheme()
        // Vector basemap set up for navigation (labels, night colours, buildings, light).
        if (!googleStyleActive()) {
            com.overdrive.app.navmap.glass.MapGlassBasemap.apply(style, night, com.overdrive.app.navmap.nav.MapNetworking.lang)
        }

        // Hazards: clustered so dense corridors collapse into a count at low zoom; the glass layers draw them.
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

        // 3D buildings before the overlay: the route ribbon goes right above the topmost extrusion.
        // The Google raster basemap has no building footprints, so 3D is vector-only.
        if (googleStyleActive()) {
            findViewById<View>(R.id.fabMap3d)?.visibility = View.GONE
        } else {
            setup3dBuildings(style)
            findViewById<View>(R.id.fabMap3d)?.visibility = View.VISIBLE
        }

        // POI (EV charging / fuel) markers along the route — created empty,
        // populated when a route is chosen + the POI toggle is on. Under the overlay's markers.
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

        // Glass overlay (alternates, route, congestion, turn arrow, hazards, pins); redraws the live trip.
        glassLayers.install(style, night, glassLowTier)
        glassLayers.installHazards(style, HAZARD_SOURCE_ID)
        applyHazardFilter(hazardFilterMode)

        // Dropped-pin marker — a single transient pin shown where the user
        // long-presses the map (the "save a place" affordance). Its own
        // source/layer so it's independent of the itinerary markers and can
        // be cleared the moment the place sheet is dismissed. Drawn on top.
        if (style.getImage(ICON_DROPPED_PIN) == null) {
            // A style reload (theme switch) wipes registered images, so onStyleLoaded re-runs
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

        // 6+7) Tap-to-verify (query the hazard symbol layer at the tap point) and
        //      refetch on every camera-idle (debounced inside). Wired ONCE — a style
        //      reload re-runs onStyleLoaded but the map listeners persist, so a guard
        //      stops a duplicate registration firing each callback twice.
        if (!listenersWired) {
            map?.addOnMapClickListener { latLng -> onMapTap(latLng) }
            map?.addOnCameraIdleListener {
                scheduleRefetch()
                // Debounced Google copyright refresh.
                if (::googleMap.isInitialized) map?.let { m -> googleMap.onCameraIdle(m) }
            }
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
        // the streets around frequent places, on Wi-Fi, for a map with no network later (once a session, a while after
        // the map is up)
        if (!clusterMode && !placesPrefetchQueued) {
            placesPrefetchQueued = true
            mainHandler.postDelayed(prefetchPlacesTask, PLACES_PREFETCH_DELAY_MS)
        }
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
        clusterTrafficListener?.let { NavSession.removeTrafficListener(it) }
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
                        onGuidanceRouteChanged(route)
                        // Reveal the turn-by-turn banner on the cluster. The cluster
                        // never runs startGuidance() (that's the head-unit-only entry
                        // point), so without this the banner stays at its XML default
                        // (gone) and every maneuver text update from tickGuidance lands
                        // on an invisible card — the "no turn-by-turn on the cluster"
                        // bug. Seed it with the trip total so it isn't blank for the
                        // ~1s until the first guidance tick fills in the next maneuver.
                        showManeuverBanner(
                            getString(R.string.roadsense_map_nav_started, st.destLabel), route)
                        showNavHud(true)
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
                        onGuidanceRouteChanged(route)
                        lastSpokenInstruction = null
                        // Reseed the banner to the NEW route immediately + drop the old
                        // route's turn glyph, so the prior route's maneuver text/ETA/icon
                        // don't linger for the ~1s until the next guidance tick repaints.
                        showManeuverBanner(
                            getString(R.string.roadsense_map_nav_started, st.destLabel), route)
                        setManeuverIcon(0)   // 0 → no glyph; next tick sets the real one
                        // renderRoute above re-framed to the whole-route OVERVIEW. Snap
                        // back into immersive follow (mirrors the nav-start branch), so a
                        // route switch while PARKED doesn't stay zoomed out.
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
                        showNavHud(false)
                    }
                } else {
                    // Trip cleared on infotainment → wipe + resume idle follow.
                    navigating = false
                    clusterNavRoute = null
                    mainHandler.removeCallbacks(guidanceRunnable)
                    guidance.stop()
                    findViewById<View>(R.id.navBanner)?.visibility = View.GONE
                    findViewById<View>(R.id.navBannerIcon)?.visibility = View.GONE
                    showNavHud(false)
                    endLiveTraffic()
                    glassLayers.clear()
                    if (!mapStopped) startClusterFollow()
                }
            }
        }
        // Live-traffic refreshes published by the head unit (congestion + banner ETA only).
        clusterTrafficListener = NavSession.addTrafficListener { t -> mainHandler.post { onClusterTraffic(t) } }
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
            ForwardGeocoder.endSession()   // abandoned without a pick
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
        val hazardHit = mlMap.queryRenderedFeatures(box, com.overdrive.app.navmap.glass.MapGlassLayers.HAZARD_GEM_LAYER_ID)
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
            val altHit = mlMap.queryRenderedFeatures(box, com.overdrive.app.navmap.glass.MapGlassLayers.ALT_LAYER_ID)
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
        if (mapStopped) return
        val root = findViewById<View>(R.id.mapRoot)
        val bar = Snackbar.make(root, text, Snackbar.LENGTH_SHORT)
        // Anchor above the highest VISIBLE bottom control: the zoom capsule sits over fabLocate, so
        // anchoring to fabLocate hid zoom-out; in immersive nav only fabEndNav is up. Else unanchored.
        val anchor = when {
            navigating -> findViewById<View>(R.id.fabEndNav)?.takeIf { it.visibility == View.VISIBLE }
            else -> listOf(R.id.zoomControls, R.id.fabLocate).firstNotNullOfOrNull { id ->
                findViewById<View>(id)?.takeIf { it.visibility == View.VISIBLE }
            }
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

    /** Hazard filter by severity (no refetch); HIDDEN hides every hazard, clusters included. */
    private fun applyHazardFilter(mode: Int) {
        hazardFilterMode = mode
        val minSeverity = when (mode) {
            HAZARD_FILTER_SEVERE -> 3
            HAZARD_FILTER_MODERATE -> 2
            else -> 1
        }
        glassLayers.setHazardFilter(minSeverity, mode != HAZARD_FILTER_HIDDEN)
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
            .getBoolean(KEY_MAP_3D, true)
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
            // Over the 2D footprints and under the labels; the route ribbon is added above it later.
            try {
                style.addLayerAbove(ext, MAP_2D_BUILDING_LAYER_ID)
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
    // Google basemap (bring your own key): host, attribution, layers menu
    // ---------------------------------------------------------------------

    /** True while the loaded basemap is the Google raster style. */
    private fun googleStyleActive(): Boolean = ::googleMap.isInitialized && googleMap.googleStyleActive

    /** Activity side of [GoogleMapController]; callbacks may arrive off the main thread. */
    private val googleHost = object : GoogleMapController.Host {
        override fun isNight(): Boolean = isNightTheme()

        // At night: the land colour of the night roadmap, so tiles still loading don't show as black squares.
        override fun backgroundHex(): String =
            if (isNightTheme()) com.overdrive.app.navmap.glass.MapGlassBasemap.N_LAND else m3Hex("surface")

        override fun requestStyleReload() = runOnUiThread { requestGoogleStyleReload() }

        override fun onGoogleNotice(notice: GoogleMapController.Notice, resetAtMs: Long) = runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            updateMapLayersFab()
            if (clusterMode) return@runOnUiThread
            val at = android.text.format.DateFormat.getTimeFormat(this@RoadSenseMapActivity)
                .format(java.util.Date(resetAtMs))
            showSnackbar(when (notice) {
                GoogleMapController.Notice.TRAFFIC_PAUSED -> getString(R.string.navmap_google_traffic_paused, at)
                GoogleMapController.Notice.MAP_PAUSED -> getString(R.string.navmap_google_map_paused, at)
                GoogleMapController.Notice.MAP_UNAVAILABLE -> getString(R.string.navmap_google_map_unavailable)
                GoogleMapController.Notice.KEY_PROBLEM -> getString(R.string.navmap_google_key_problem)
            })
        }

        override fun mainHandler(): Handler = this@RoadSenseMapActivity.mainHandler

        override fun onAttributionChanged() = runOnUiThread {
            if (!isFinishing && !isDestroyed) bindGoogleAttribution()
        }
    }

    /** Basemap swap asked by the controller or the layers menu; waits out an in-flight
     *  theme reload (up to ~5 s) instead of being dropped by its coalescing guard. */
    private val googleStyleReload = object : Runnable {
        var waits = 0
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (styleReloadInFlight && waits++ < 20) {
                mainHandler.postDelayed(this, 250L)
                return
            }
            waits = 0
            // 5 s with no callback: that load is lost, not slow
            styleReloadInFlight = false
            reloadStyleForTheme()
        }
    }

    private fun requestGoogleStyleReload() {
        mainHandler.removeCallbacks(googleStyleReload)
        googleStyleReload.waits = 0
        mainHandler.post(googleStyleReload)
    }

    /** Google logo + copyright while the Google basemap shows; MapLibre's logo hides meanwhile. */
    private fun bindGoogleAttribution() {
        if (!::googleMap.isInitialized) return
        val google = googleMap.googleStyleActive
        applyAttributionChrome()
        // Satellite imagery is dark at any hour, so it takes the night (white) logo.
        val imagery = googleMap.mapTypePref == GoogleMapType.SATELLITE ||
            googleMap.mapTypePref == GoogleMapType.HYBRID
        findViewById<GoogleAttributionView>(R.id.googleAttribution)?.bind(
            visible = google,
            night = isNightTheme() || (google && imagery),
            copyright = if (clusterMode) null else googleMap.copyright()   // glanceable cluster: logo only
        )
    }

    /** Layers FAB: head unit only, shown while a Google layer is usable; accented while traffic shows. */
    private fun updateMapLayersFab() {
        val fab = findViewById<FloatingActionButton>(R.id.fabMapLayers) ?: return
        val caps = if (!clusterMode && ::googleMap.isInitialized) googleMap.caps() else null
        if (caps == null || !(caps.googleBasemap || caps.traffic)) {
            fab.visibility = View.GONE
            return
        }
        fab.visibility = View.VISIBLE
        val attr = if (googleMap.trafficShown) androidx.appcompat.R.attr.colorPrimary
                   else com.google.android.material.R.attr.colorSurfaceContainerHighest
        fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(fab, attr)
        )
    }

    /** Map type (single choice) + live traffic (checkbox) popup, built like the hazard filter. */
    private fun showMapLayersMenu(anchor: View) {
        if (!::googleMap.isInitialized) return
        val caps = googleMap.caps()
        val types = listOf(
            GoogleMapType.ROADMAP to R.string.navmap_google_type_default,
            GoogleMapType.SATELLITE to R.string.navmap_google_type_satellite,
            GoogleMapType.HYBRID to R.string.navmap_google_type_hybrid,
            GoogleMapType.TERRAIN to R.string.navmap_google_type_terrain
        )
        // Item ids: 1..types.size map types, then OverDrive map, then traffic.
        val idOverDrive = types.size + 1
        val idTraffic = types.size + 2
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
        if (caps.googleBasemap) {
            types.forEachIndexed { i, (_, label) -> menu.menu.add(1, i + 1, i, getString(label)) }
            menu.menu.add(1, idOverDrive, types.size, getString(R.string.navmap_google_type_overdrive))
            menu.menu.setGroupCheckable(1, true, true)
            val checkedId = if (!googleMap.googleBasemapPref) idOverDrive
                            else types.indexOfFirst { it.first == googleMap.mapTypePref } + 1
            menu.menu.findItem(checkedId)?.isChecked = true
        }
        if (caps.traffic) {
            menu.menu.add(2, idTraffic, types.size + 1, getString(R.string.navmap_google_traffic)).apply {
                isCheckable = true
                isChecked = googleMap.trafficPref
            }
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                idTraffic -> {
                    val on = !item.isChecked
                    googleMap.setTraffic(map?.style, on)
                    updateMapLayersFab()
                    showSnackbar(getString(
                        if (on) R.string.navmap_google_traffic_on else R.string.navmap_google_traffic_off))
                }
                idOverDrive -> selectMapLayer(google = false, type = googleMap.mapTypePref)
                else -> types.getOrNull(item.itemId - 1)?.let { selectMapLayer(google = true, type = it.first) }
            }
            true
        }
        menu.show()
    }

    /** Persist the basemap choice and swap the style; no-op when it is already the choice. */
    private fun selectMapLayer(google: Boolean, type: GoogleMapType) {
        if (googleMap.googleBasemapPref == google && googleMap.mapTypePref == type) return
        googleMap.googleBasemapPref = google
        googleMap.mapTypePref = type
        requestGoogleStyleReload()
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
     * Itinerary pins for [route]: numbered via-stops and the destination. [stops] is the ordered
     * itinerary after the origin; without one (the cluster mirror) the destination is the route's end.
     */
    private fun renderItineraryMarkers(route: NavRoute, stops: List<SearchResult>) {
        if (route.points.isEmpty()) { clearItineraryMarkers(); return }
        val vias = if (stops.size >= 2) {
            stops.subList(0, stops.size - 1).map { com.overdrive.app.navmap.nav.GeoPoint(it.lat, it.lng) }
        } else {
            emptyList()
        }
        val dest = stops.lastOrNull()?.let { com.overdrive.app.navmap.nav.GeoPoint(it.lat, it.lng) } ?: route.points.last()
        glassLayers.setPins(vias, dest)
    }

    private fun clearItineraryMarkers() {
        glassLayers.setPins(emptyList(), null)
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
            if (isStop) {
                // by place, not the index it opened with: a stop may have left the trip meanwhile
                val idx = routeStops.indexOfFirst { kotlin.math.abs(it.lat - lat) < 1e-5 && kotlin.math.abs(it.lng - lng) < 1e-5 }
                if (idx >= 0) removePoiStop(idx)
            } else addPoiAsStop(SearchResult(label, lat, lng))
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
        if (routeStops.isEmpty()) { if (navigating) endTrip() else clearRoutePreview() } else recomputeItinerary()
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
    private fun persistTrip(guiding: Boolean = navigating) {
        if (clusterMode) return
        // set out for this destination (not one just replaced), or restored as such and not guiding yet
        val dest = routeStops.lastOrNull()
        persistedSetOut = dest != null &&
            ((navigating && guidance.hasSetOut && dest == guidedDest) || dest == resumeSetOutTo)
        NavTripStore.save(applicationContext, ArrayList(routeStops), guiding, setOut = persistedSetOut)
        // where the car is left, for the stops as they are now
        if (navigating) {
            noteLeftAt(lastFix)
            touchTrip(leftAtDest, leftAtStop != null, leftStanding)
        }
    }

    /** What the saved trip says about [NavGuidanceEngine.hasSetOut], so it's saved again once that changes. */
    private var persistedSetOut = false
    /** The destination guidance (and its set-out latch) belongs to. */
    private var guidedDest: SearchResult? = null
    /** A guiding trip restored after a restart had set out: its next guidance starts set out. */
    private var resumeSetOutTo: SearchResult? = null

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
        val start = NavTripStore.takeStart(applicationContext, START_REQUEST_MAX_AGE_MS)
        // the driver's yes is the newer wish: guidance already running ends first (keeping the trip just loaded)
        endOldGuidanceFor(start)
        if (start) {
            briefingStartAt = android.os.SystemClock.elapsedRealtime()
            briefingWaitedForFix = false
        }
        val myStartAt = briefingStartAt
        val gen = routeGen
        // Need an origin to recompute. Use the last fix if we have one, else fetch.
        val resume = resume@{
            // a trip that was guiding, picked up again with the car already at its destination: it arrived meanwhile
            val dest = trip.stops.lastOrNull()
            val here = lastFix
            // (not under way yet, or with a stop still left: only after a long while away)
            val away = trip.awayMs > STALE_GUIDANCE_GAP_MS
            val freshHere = here?.takeIf { fixIsFresh(RESTORE_FIX_MAX_AGE_MS) }
            // (not at its last stop, nor by the next one it was left by: nearer that than the destination)
            val destM = if (dest != null && freshHere != null) guidance.haversineMeters(freshHere.lat, freshHere.lng, dest.lat, dest.lng)
            else Double.MAX_VALUE
            val stopsBy = listOfNotNull(
                trip.stopLeftAt,
                trip.stops.takeIf { trip.leftAtStop && it.size >= 2 }?.get(0)?.let { com.overdrive.app.navmap.nav.GeoPoint(it.lat, it.lng) }
            )
            val atDest = destM <= STALE_ARRIVED_M &&
                stopsBy.all { destM < guidance.haversineMeters(freshHere!!.lat, freshHere.lng, it.lat, it.lng) }
            // (or left by it when guidance stopped, the car switched off there, and back a while later; or guidance gone so
            // long it's done with, wherever the car went meanwhile)
            val awayFromLeft = if (trip.leftStanding) trip.awayMs > LEFT_PARKED_RETURN_MS
            else trip.measuredAwayMs > STALE_GUIDANCE_GAP_MS
            if (!start && trip.navigating &&
                ((atDest && away) || (awayFromLeft && trip.leftAtDest) || trip.awayMs > STALE_TRIP_MS)
            ) {
                NavTripStore.clear(applicationContext)
                return@resume
            }
            routeStops.clear()
            routeStops.addAll(trip.stops)
            resumeStopLeftAt = trip.stopLeftAt.takeIf { !start && trip.navigating }
            // left standing by the next stop, back a long while later: visited
            if (!start && trip.navigating && awayFromLeft && trip.leftAtStop && routeStops.size >= 2) {
                val visited = routeStops.removeAt(0)
                NavTripStore.touch(applicationContext)
                NavTripStore.stopLeft(applicationContext, visited.lat, visited.lng)
                resumeStopLeftAt = com.overdrive.app.navmap.nav.GeoPoint(visited.lat, visited.lng)
            }
            // under way before, picked up a long while later, or already at its only stop: it can still end where the car is
            resumeSetOutTo = if (!start && trip.navigating && (trip.setOut || away || (atDest && trip.stops.size <= 1))) dest
            else null
            if (!start) showSnackbar(getString(R.string.roadsense_map_trip_restored))
            if (start && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                // left before it could route (the reverse camera, another app): it carries on when the map is back
                routeOnReturn = true
                routeOnReturnSince = android.os.SystemClock.elapsedRealtime()
                fixWaitOnReturn = false
                NavTripStore.clear(applicationContext)
            } else {
                // (a guiding trip picked up a good while later comes back as a preview: it may have been finished without
                // the map; Go takes it up again)
                recomputeItinerary(autoStart = (trip.navigating && trip.awayMs <= RESUME_AS_PREVIEW_MS) || start)
            }
        }
        // a fix from before the map was left (GPS stops with it) is no origin for a new trip
        val freshFix = lastFix != null && fixIsFresh(RESTORE_FIX_MAX_AGE_MS)
        if (freshFix) { resume(); return }
        ioExecutor().execute {
            val fix = RoadSenseHazardApiClient.fetchCurrentLocation()
            mainHandler.post {
                if (isFinishing || isDestroyed) {
                    // a briefing yes the map never got to: no stale plan for a later map
                    if (start) NavTripStore.clear(applicationContext)
                    return@post
                }
                if (fix != null) lastFix = fix
                // a route the driver searched or cleared meanwhile stands
                if (routeGen != gen) {
                    if (start && briefingStartAt == myStartAt) briefingStartAt = 0L
                    return@post
                }
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
                // Pre-warm the Google search/routing connections (no-ops without Google).
                ioExecutor().execute {
                    try { ForwardGeocoder.warmUp(); NavRouting.warmUp() } catch (_: Throwable) {}
                }
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
            ForwardGeocoder.endSession()   // abandoned without a pick
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
        mainHandler.postDelayed(
            r, if (ForwardGeocoder.googleActive()) GOOGLE_AUTOCOMPLETE_DEBOUNCE_MS else AUTOCOMPLETE_DEBOUNCE_MS
        )
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
        // A Google place prediction has no coordinates yet: look them up, then continue.
        if (result.needsResolve) {
            resolvePlaceThen(result) { onSearchResultChosen(it) }
            return
        }
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

    /** Resolves a place prediction's coordinates off the main thread; only the latest request continues. */
    private fun resolvePlaceThen(result: SearchResult, then: (SearchResult) -> Unit) {
        showSnackbar(getString(R.string.navmap_google_finding_place))
        val token = ++placeResolveToken
        ioExecutor().execute {
            val resolved = try { ForwardGeocoder.resolve(result) } catch (_: Throwable) { null }
            mainHandler.post {
                if (isFinishing || isDestroyed || token != placeResolveToken) return@post
                val located = resolved?.takeIf { !it.needsResolve }
                if (located != null) then(located)
                else showSnackbar(getString(R.string.navmap_google_place_failed))
            }
        }
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
                unroutedSearch = true
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
        unroutedSearch = true
        recomputeItinerary()
    }

    /** A new search's destination hasn't been routed yet (so a failure leaves no trip to restore). */
    private var unroutedSearch = false
    /** An itinerary's routing is out (its result replans if the stops changed meanwhile). */
    private var recomputing = false
    /** When a briefing trip was set aside for the map to come back. */
    private var routeOnReturnSince = 0L
    /** The stops [previewRoutes] were planned through. */
    private var previewStops: List<SearchResult> = emptyList()

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
     * unhelpful text. Reads NavRouting.lastError, set on the call that just
     * returned empty (single foreground routing call at a time).
     */
    private fun routeFailureMessage(): String = when (NavRouting.lastError) {
        NavRouting.Error.NO_KEY -> getString(R.string.roadsense_map_need_key)
        NavRouting.Error.AUTH -> getString(R.string.roadsense_map_route_key_rejected)
        NavRouting.Error.TIMEOUT,
        NavRouting.Error.NETWORK -> getString(R.string.roadsense_map_route_network)
        NavRouting.Error.HTTP -> {
            val code = NavRouting.lastHttpCode
            if (code > 0) getString(R.string.roadsense_map_route_http, code)
            else getString(R.string.roadsense_map_route_failed)
        }
        NavRouting.Error.GOOGLE_AUTH -> getString(R.string.navmap_google_route_key_problem)
        NavRouting.Error.GOOGLE_LIMIT -> getString(R.string.navmap_google_route_limit)
        // EMPTY (200 but no path) / NONE — genuinely no route between the points.
        else -> getString(R.string.roadsense_map_route_failed)
    }

    private fun recomputeItinerary(autoStart: Boolean = false, retry: Boolean = false) {
        cancelRouteRetry()
        val gen = routeGen
        if (!retry) routeRetries = 0
        autoRouting = autoStart
        routeOnReturn = false
        fixWaitOnReturn = false
        routeOnFreshFix = false
        if (!autoStart) briefingStartAt = 0L
        // Persist the new itinerary so it survives leaving the map (a restored guiding trip still routing as guiding).
        persistTrip(guiding = navigating || (autoStart && briefingStartAt == 0L))
        // The itinerary view should reflect the current stops immediately even
        // while the route is being (re)computed.
        if (routeStops.isEmpty()) {
            clearRoutePreview()
            return
        }
        val freshEnough = !(autoStart && briefingStartAt > 0L) || fixIsFresh(RESTORE_FIX_MAX_AGE_MS)
        val origin = lastFix?.takeIf { freshEnough }
        if (origin == null) {
            lastRouteFailNoFix = true
            if (autoStart && briefingStartAt > 0L) briefingWaitedForFix = true
            // only the first and the last failure are shown
            if (!retryRoute(autoStart) || !retry) showSnackbar(getString(R.string.roadsense_map_no_location))
            return
        }
        lastRouteFailNoFix = false
        // (a stop the car is right at, or just through, isn't planned again)
        if (navigating && !clusterMode) tripStops.beforeReroute(viaStops(), origin.lat, origin.lng)?.let { dropDone(it) }
        val destination = routeStops.last()
        val destLat = destination.lat
        val destLng = destination.lng
        val destLabel = destination.label
        // Snapshot the itinerary for the worker so a concurrent edit can't desync.
        val stopsSnapshot = ArrayList(routeStops)
        val useVia = stopsSnapshot.size > 1

        if (!retry && android.os.SystemClock.elapsedRealtime() >= goBackUntil) showSnackbar(getString(R.string.roadsense_map_routing))
        recomputing = true
        // (an edit while guiding: from the way the car is going)
        val heading = if (navigating && !clusterMode) routeHeading() else null
        ioExecutor().execute {
            val routes = if (useVia) {
                val pts = ArrayList<com.overdrive.app.navmap.nav.GeoPoint>(stopsSnapshot.size + 1)
                pts.add(com.overdrive.app.navmap.nav.GeoPoint(origin.lat, origin.lng))
                stopsSnapshot.forEach { pts.add(com.overdrive.app.navmap.nav.GeoPoint(it.lat, it.lng)) }
                NavRouting.routeVia(pts, heading)
            } else {
                NavRouting.routesWithAlternates(
                    origin.lat, origin.lng, destLat, destLng, ROUTE_ALTERNATES, heading
                )
            }
            mainHandler.post {
                recomputing = false
                if (isFinishing || isDestroyed) return@post
                // a newer itinerary or an ended trip: an old result never shows or starts anything
                if (gen != routeGen) return@post
                // nor a briefing's, unseen: a map left meanwhile carries on when it's back
                if (autoStart && briefingStartAt > 0L &&
                    !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    autoRouting = false
                    routeOnReturn = true
                    routeOnReturnSince = android.os.SystemClock.elapsedRealtime()
                    fixWaitOnReturn = briefingWaitedForFix
                    NavTripStore.clear(applicationContext)
                    return@post
                }
                if (routes.isEmpty()) {
                    if (!retryRoute(autoStart) || !retry) showSnackbar(routeFailureMessage())
                    return@post
                }
                autoRouting = false
                unroutedSearch = false
                if (navigating && !clusterMode && routeStops != stopsSnapshot) {
                    lastFix?.let { replanFrom(it) }
                    return@post
                }
                // guiding already (the driver started it), or about to: the briefing's own start is done
                val briefingStart = autoStart && !navigating && briefingStartAt > 0L
                if (navigating || autoStart) briefingStartAt = 0L
                previewRoutes = routes
                previewStops = stopsSnapshot
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
                    // (another destination: the stops done with were the old trip's)
                    val newDest = stopsSnapshot.lastOrNull()
                    val oldDest = guidedDest
                    if (newDest != null && oldDest != null &&
                        guidance.haversineMeters(newDest.lat, newDest.lng, oldDest.lat, oldDest.lng) > NEW_DEST_M
                    ) {
                        tripStops.forgetLeftStop()
                        NavTripStore.clearStopLeft(applicationContext)
                    }
                    guidedDest = stopsSnapshot.lastOrNull()
                    replanPending = false
                    clearLeftAt()
                    tickElapsedAt = 0L
                    tickUptimeAt = 0L
                    resetArrivalTimers()
                    tripStops.routeInstalled(viaStops(stopsSnapshot), lastFix?.lat ?: Double.NaN, lastFix?.lng ?: Double.NaN)
                    resetRouteTrim()
                    lastSpokenInstruction = null
                    NavSession.publishRoute(routes[0], destLabel)
                    onGuidanceRouteChanged(routes[0])
                    loadHazardCountsForRoutes(routes)
                } else if (autoStart) {
                    // Restoring an active nav trip → resume turn-by-turn straight
                    // into the immersive view (no route-options sheet).
                    loadHazardCountsForRoutes(routes)
                    startGuidance(routes[0], destLabel)
                    if (briefingStart && !clusterMode) offerClusterMap()
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

    /** Draw all candidate routes: alternates quiet underneath, the selected route on top. */
    private fun drawRoutePreview(routes: List<NavRoute>, selectedIdx: Int) {
        val selected = routes[selectedIdx]
        glassLayers.setAlternates(routes, selectedIdx)
        showGlassRoute(selected)
        showRouteTraffic(selected)
        // Destination + numbered stop pins for the selected route's itinerary.
        renderItineraryMarkers(selected, routeStops)
    }

    /** Windowed ribbon while following during guidance; the whole route in preview and overview. */
    private fun showGlassRoute(route: NavRoute?) {
        glassLayers.setRoute(route, navigating && cameraRig.mode != com.overdrive.app.navmap.motion.NavCameraRig.Mode.OVERVIEW)
    }

    /** A new guided route (start, reroute, switch) starts un-trimmed. */
    private fun resetRouteTrim() {
        trafficTraveledM = 0.0
        guidance.activeRoute()?.let { showGlassRoute(it) }
        glassLayers.resetProgress()
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

        findViewById<View>(R.id.btnRouteSheetClose)?.setOnClickListener { if (navigating) hideRouteSheet() else clearRoutePreview() }
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
        bindRouteProvider(routes)

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

    /** Routing credit above Start: Google routes, or the Valhalla provider while Google map content is shown. */
    private fun bindRouteProvider(routes: List<NavRoute>) {
        val tv = findViewById<TextView>(R.id.tvRouteProvider) ?: return
        val google = com.overdrive.app.navmap.google.GoogleMapsConfig.cached()
        when {
            routes.firstOrNull()?.provider == RouteProvider.GOOGLE -> {
                tv.setText(R.string.navmap_google_routes_by_google)
                tv.visibility = View.VISIBLE
            }
            google.basemapUsable || google.trafficUsable -> {
                tv.text = getString(R.string.navmap_google_routing_provided_by, "Stadia Maps")
                tv.visibility = View.VISIBLE
            }
            else -> tv.visibility = View.GONE
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
            if (navigating) endTrip() else clearRoutePreview()
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

    /** About a minute of retries, for a restored trip only (it starts guidance itself). */
    private fun retryRoute(autoStart: Boolean): Boolean {
        if (!autoStart || clusterMode || routeRetries >= ROUTE_RETRY_MAX) {
            autoRouting = false
            // a search that never got a route isn't a trip to bring back later
            if (!autoStart && !navigating && !clusterMode && unroutedSearch) NavTripStore.clear(applicationContext)
            // a briefing trip that never routed isn't kept for a later map, unless all it lacks is a fix
            if (autoStart && briefingStartAt > 0L) {
                if (lastRouteFailNoFix && android.os.SystemClock.elapsedRealtime() - briefingStartAt < FIX_WAIT_MAX_MS) {
                    routeOnFreshFix = true
                } else {
                    briefingStartAt = 0L
                    NavTripStore.clear(applicationContext)
                }
            }
            return false
        }
        routeRetries++
        mainHandler.postDelayed(routeRetry, ROUTE_RETRY_MS)
        return true
    }

    private fun cancelRouteRetry() {
        routeGen++
        autoRouting = false
        mainHandler.removeCallbacks(routeRetry)
    }

    // Ended or arrived: guidance stops and the trip goes with it, so a style reload can't draw it back.
    private fun endTrip(keepSaved: Boolean = false) {
        stopGuidance(keepSaved)
        clearRoutePreview(keepSaved = keepSaved)
    }

    /** Fully clear the preview: routes, lines, sheet, locked destination, stops. */
    private fun clearRoutePreview(keepActive: Boolean = false, keepSaved: Boolean = false) {
        cancelRouteRetry()
        resumeSetOutTo = null
        unroutedSearch = false
        previewStops = emptyList()
        previewRoutes = emptyList()
        lockedDestLat = Double.NaN
        lockedDestLng = Double.NaN
        // Trip abandoned → drop the persisted itinerary too.
        if (!clusterMode && !keepSaved) NavTripStore.clear(applicationContext)
        // Drop the whole itinerary + any pending add/edit mode so a fresh search
        // starts a brand-new trip.
        routeStops.clear()
        addingStop = false
        editingStopIndex = -1
        findViewById<android.widget.LinearLayout>(R.id.routeStopsContainer)?.removeAllViews()
        glassLayers.setAlternates(emptyList(), -1)
        if (!keepActive) {
            glassLayers.setRoute(null, false)
            glassLayers.setManeuverArrow(null, null)
            showTraffic(null, emptyList())
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
        tripStops.routeSwitched(lastFix?.lat ?: Double.NaN, lastFix?.lng ?: Double.NaN)
        resetRouteTrim()   // switched to a different route → reset trim to the new line
        lastSpokenInstruction = null
        drawRoutePreview(previewRoutes, idx)
        // Mirror the driver's mid-nav route switch to the cluster too.
        if (!clusterMode) NavSession.publishRoute(chosen, previewDestLabel)
        onGuidanceRouteChanged(chosen)
        showSnackbar(getString(R.string.roadsense_map_route_switched))
    }

    /** Draw [route] and frame it on screen. */
    private fun renderRoute(route: NavRoute) {
        showGlassRoute(route)
        showRouteTraffic(route)
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
        // a restored trip still routing must not switch this guidance to its own route
        cancelRouteRetry()
        briefingStartAt = 0L
        hideRouteSheet()
        guidance.start(route)
        val planned = previewStops.takeIf { it.isNotEmpty() } ?: ArrayList(routeStops)
        guidedDest = planned.lastOrNull()
        // restored after a restart, it had set out: so it can still end where the car is parked
        val resumed = resumeSetOutTo != null && resumeSetOutTo == guidedDest
        if (resumed) guidance.resumeSetOut()
        resumeSetOutTo = null
        tripTouchedAt = 0L
        rerouteGen++
        rerouting = false
        rerouteAgain = false
        replanPending = false
        clearLeftAt()
        tickElapsedAt = 0L
        tickUptimeAt = 0L
        tripStops.begin()
        goBackUntil = 0L
        pendingGoBack = null
        // (nor where its last stop was done with, the car still there)
        resumeStopLeftAt?.let { if (resumed) tripStops.stopLeftAt(it.lat, it.lng) }
        // (another trip's is no more)
        if (!resumed || resumeStopLeftAt == null) NavTripStore.clearStopLeft(applicationContext)
        resumeStopLeftAt = null
        tripStops.routeInstalled(viaStops(planned), lastFix?.lat ?: Double.NaN, lastFix?.lng ?: Double.NaN)
        navigating = true
        // a new trip's first tick isn't "back after a long while"
        NavSession.guidanceTick(headUnit = !clusterMode)
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
        onGuidanceRouteChanged(route)   // live-traffic refresh for a Google route
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
        showNavHud(true)
        // Drop any prior trip's turn glyph until the first guidance tick repaints the
        // real one — switching destination mid-nav reaches here without a stopGuidance
        // in between, so the old arrow would otherwise linger ~1s against the new
        // trip's text. Mirrors the cluster route-changed branch.
        setManeuverIcon(0)
        if (hud == null) findViewById<FloatingActionButton>(R.id.fabEndNav)?.visibility = View.VISIBLE
        mainHandler.removeCallbacks(guidanceRunnable)
        mainHandler.post(guidanceRunnable)
    }

    // A Trip Briefing yes started this guidance: the cluster map one tap or one "yes" away, unless it's up there already.
    private fun offerClusterMap() {
        ioExecutor().execute {
            if (isClusterProjectionActive()) return@execute
            mainHandler.post {
                if (isFinishing || isDestroyed || !navigating || clusterMode) return@post
                val root = findViewById<View>(R.id.mapRoot) ?: return@post
                var voice: com.overdrive.app.voice.TripBriefingAsk.Handle? = null
                val bar = Snackbar.make(root, getString(R.string.roadsense_map_cluster_offer), CLUSTER_OFFER_SHOW_MS)
                    .setAction(R.string.roadsense_map_cluster_show) {
                        voice?.cancel()
                        startClusterMap()
                    }
                findViewById<View>(R.id.fabEndNav)?.takeIf { it.visibility == View.VISIBLE }?.let { bar.anchorView = it }
                bar.show()
                // asked out loud too (a recorded clip where the head unit has no speech engine), answered yes or no
                val ask = com.overdrive.app.voice.TripBriefingAsk(
                    "cluster-" + android.os.SystemClock.elapsedRealtime(), getString(R.string.roadsense_map_cluster_offer),
                    null, 0, null, emptyList(), CLUSTER_OFFER_SHOW_MS.toLong(), CLUSTER_OFFER_CLIP, true
                )
                voice = try {
                    com.overdrive.app.voice.VoiceAssistantRuntime.askTripBriefing(ask) { answer, _, _ ->
                        mainHandler.post { onClusterAnswer(answer, bar) }
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    private fun onClusterAnswer(answer: String, bar: Snackbar) {
        if (isFinishing || isDestroyed) return
        when (answer) {
            com.overdrive.app.voice.TripBriefingAsk.ANSWER_YES -> {
                bar.dismiss()
                if (navigating) startClusterMap()
            }
            com.overdrive.app.voice.TripBriefingAsk.ANSWER_NO, com.overdrive.app.voice.TripBriefingAsk.ANSWER_LATER -> bar.dismiss()
        }
    }

    private fun startClusterMap() {
        ioExecutor().execute {
            val projecting = try {
                val conn = com.overdrive.app.util.DaemonHttpClient.open("/api/navmap/cluster/start", "POST", 1500, 8000)
                try {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                    conn.responseCode in 200..299 &&
                        org.json.JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                            .optBoolean("projecting", false)
                } finally {
                    conn.disconnect()
                }
            } catch (_: Throwable) {
                false
            }
            if (!projecting) mainHandler.post {
                if (!isFinishing && !isDestroyed) showSnackbar(getString(R.string.roadsense_map_cluster_failed))
            }
        }
    }

    /**
     * Move into the immersive driving view (close zoom, 3D tilt, heading-up) at the
     * route's origin right away, so navigation doesn't sit at the overview framing
     * while the car is still stationary.
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
        motionModel.seedBearing(bearing)
        cameraRig.reset()
        mainHandler.removeCallbacks(recenterAfterFree)
        // With a fix the frame loop eases into the follow view; without one, show the immersive view now.
        if (motionEstimator.hasFix()) {
            startMotionFollow()
            return
        }
        val pos = org.maplibre.android.camera.CameraPosition.Builder()
            .target(target)
            .zoom(IMMERSIVE_ZOOM)
            .tilt(IMMERSIVE_TILT)
            .bearing(bearing)
            .build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), GUIDANCE_CAM_ANIM_MS)
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
            if (clusterMode || hud != null) View.GONE else View.VISIBLE
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
        // Level the camera back to top-down north-up, dropping the follow camera's puck anchor padding.
        map?.let { m ->
            val pos = org.maplibre.android.camera.CameraPosition.Builder()
                .target(m.cameraPosition.target)
                .tilt(0.0)
                .bearing(0.0)
                // the nav zoom's tiles still cover a street view; zooming out to the overview needed a screen of new ones
                .zoom(m.cameraPosition.zoom.coerceIn(DEFAULT_ZOOM, FOLLOW_ZOOM))
                .padding(0.0, 0.0, 0.0, 0.0)
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
     * [centreOn] at [FOLLOW_ZOOM]; the route preview framing and the nav-exit level-off
     * intentionally stay flat.
     */
    private fun restingTilt(): Double =
        if (clusterMode || navigating) 0.0 else RESTING_TILT

    /** Pose of the puck last written to the GL layer, so a parked car rewrites (and re-renders) nothing. */
    private var puckPaintedLat: Double = Double.NaN
    private var puckPaintedLng: Double = Double.NaN
    private var puckPaintedBearing: Double = Double.NaN
    /** Camera pose last written by [driveCamera]; an unchanged pose is skipped so a parked map stays idle. */
    private val lastCameraPose = com.overdrive.app.navmap.motion.NavCameraRig.Pose().apply { lat = Double.NaN }

    private fun currentCameraPose(): com.overdrive.app.navmap.motion.NavCameraRig.Pose? {
        val cp = map?.cameraPosition ?: return null
        val t = cp.target ?: return null
        return com.overdrive.app.navmap.motion.NavCameraRig.Pose().apply {
            lat = t.latitude
            lng = t.longitude
            zoom = cp.zoom
            tilt = cp.tilt
            bearing = cp.bearing
            paddingTop = cp.padding?.getOrNull(1) ?: 0.0
        }
    }

    /** Writes this frame's follow camera; true while it is still moving. */
    private fun driveCamera(nowMono: Long): Boolean {
        val mp = map ?: return false
        if (!::mapView.isInitialized || mapView.height <= 0) return false
        if (cameraRig.needsEntry) cameraRig.follow(nowMono, currentCameraPose(), GUIDANCE_CAM_ANIM_MS.toLong())
        if (cameraRig.freeExpired(nowMono)) recenterFollow(nowMono)
        val track = motionTrack
        val s = motionModel.routeS
        val onTrack = track != null && !s.isNaN()
        val pose = cameraRig.update(
            nowMono, motionModel.lat, motionModel.lng, motionModel.cameraBearing, motionModel.speedMps,
            if (onTrack) track!!.distanceToNextKey(s) else Double.POSITIVE_INFINITY,
            if (onTrack) track!!.distanceSinceLastKey(s) else Double.POSITIVE_INFINITY,
            mapView.height, googleStyleActive(), clusterMode
        ) ?: return false
        val last = lastCameraPose
        if (!last.lat.isNaN() &&
            kotlin.math.abs(pose.lat - last.lat) < 1e-8 && kotlin.math.abs(pose.lng - last.lng) < 1e-8 &&
            kotlin.math.abs(pose.zoom - last.zoom) < 1e-4 && kotlin.math.abs(pose.tilt - last.tilt) < 1e-3 &&
            kotlin.math.abs(pose.bearing - last.bearing) < 1e-3 && kotlin.math.abs(pose.paddingTop - last.paddingTop) < 0.5
        ) return cameraRig.transitioning
        mp.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                org.maplibre.android.camera.CameraPosition.Builder()
                    .target(LatLng(pose.lat, pose.lng))
                    .zoom(pose.zoom)
                    .tilt(pose.tilt)
                    .bearing(pose.bearing)
                    .padding(0.0, pose.paddingTop, 0.0, 0.0)
                    .build()
            )
        )
        last.set(pose)
        return true
    }

    /** Back to the follow camera from free pan or overview, easing from where the camera is. */
    private fun recenterFollow(nowMono: Long) {
        mainHandler.removeCallbacks(recenterAfterFree)
        cameraRig.follow(nowMono, currentCameraPose(), RECENTER_ANIM_MS.toLong())
        guidance.activeRoute()?.let { showGlassRoute(it) }
        if (!clusterMode && navigating) findViewById<View>(R.id.fabLocate)?.visibility = View.GONE
        hud?.setFree(false)
        hud?.setOverview(false)
        startMotionFollow()
    }

    private fun setupNavHud() {
        val root = findViewById<com.overdrive.app.navmap.ui.NavHudLayout>(R.id.navHud) ?: return
        hud = try {
            com.overdrive.app.navmap.ui.NavHudController(root, object : com.overdrive.app.navmap.ui.NavHudController.Listener {
                override fun onEnd() = endTrip()
                override fun onOverview() = toggleRouteOverview()
                override fun onMute() {
                    val v = navVoice ?: return
                    v.setMuted(!v.isMuted())
                    hud?.bindMuted(v.isMuted())
                }
                override fun onRecenter() = recenterFollow(android.os.SystemClock.elapsedRealtime())
                override fun onCompass() = toggleNorthUp()
                override fun onBack() = onBackPressedDispatcher.onBackPressed()
            }).apply {
                setCluster(clusterMode)
                setImperial(com.overdrive.app.navmap.nav.MapNetworking.useMiles)
                setNight(isNightTheme())
            }
        } catch (t: Throwable) {
            Log.w("RoadSenseMap", "nav HUD unavailable", t)
            null
        }
    }

    /** Guidance chrome: the glass HUD (the legacy banner stays hidden); falls back to the banner without a HUD. */
    private fun showNavHud(visible: Boolean) {
        val h = hud
        if (h == null) {
            findViewById<View>(R.id.navBanner)?.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        findViewById<View>(R.id.navBanner)?.visibility = View.GONE
        if (!visible) {
            h.bindManeuver(null)
            h.bindThen(null, 0.0)
            h.setFree(false)
            h.setOverview(false)
            hudManeuverRef = null
            hudManeuverS = Double.NaN
        }
        h.setNight(isNightTheme())
        h.setAttributionVisible(!googleStyleActive())
        h.bindMuted(navVoice?.isMuted() == true)
        h.setGuidanceVisible(visible)
        if (hudShown != visible) {
            hudShown = visible
            applyAttributionChrome()
        }
    }

    /** The route track for [route], rebuilt when the route changes (shared by the frame loop and the HUD). */
    private fun trackFor(route: NavRoute?): com.overdrive.app.navmap.motion.RouteTrack? {
        if (route !== motionTrack?.route) {
            motionTrack = com.overdrive.app.navmap.motion.RouteTrack.of(route)
            motionModel.setTrack(motionTrack)
        }
        return motionTrack
    }

    /** 1 Hz: on-road turn arrow, maneuver card, Then tab and trip bar from the guidance engine. */
    private fun bindHudGuidance(state: NavGuidanceEngine.GuidanceState) {
        val m = state.currentManeuver
        val arrowWithin = if (motionModel.speedMps > ARROW_FAST_MPS) ARROW_FAST_M else ARROW_NEAR_M
        glassLayers.setManeuverArrow(
            guidance.activeRoute(), m?.takeIf { it.type in ARROW_TYPES && state.distanceToManeuverM <= arrowWithin }
        )
        val h = hud ?: return
        val track = trackFor(guidance.activeRoute())
        val lht = com.overdrive.app.navmap.nav.DrivingSide.isLeftHand(motionModel.lat, motionModel.lng)
        if (m !== hudManeuverRef) {
            hudManeuverRef = m
            hudManeuverS = if (m != null && track != null) track.sAtIndex(m.beginShapeIndex) else Double.NaN
            h.bindManeuver(m?.let { com.overdrive.app.navmap.ui.HudManeuver.of(it, lht) })
        }
        // Off the route the frame loop has no along-route position; the engine's distance stands in.
        if (motionModel.routeS.isNaN() || hudManeuverS.isNaN()) h.bindDistance(state.distanceToManeuverM)
        val next = state.nextManeuver
        val gap = if (m != null && next != null && track != null) track.sAtIndex(next.beginShapeIndex) - hudManeuverS else Double.NaN
        val thenWindow = (motionModel.speedMps * THEN_WINDOW_S).coerceIn(THEN_MIN_M, THEN_MAX_M)
        if (next != null && gap.isFinite() && gap in 0.0..thenWindow) {
            h.bindThen(com.overdrive.app.navmap.ui.HudManeuver.of(next, lht), gap)
        } else {
            h.bindThen(null, 0.0)
        }
        val eta = displayEtaSeconds(state.etaSeconds)
        h.bindTrip(System.currentTimeMillis() + (eta * 1000.0).toLong(), eta, state.remainingDistanceM, trafficDelaySeconds(eta, state.remainingDistanceM))
    }

    /** Remaining delay against the free-flow time (Google routes carry one); null without traffic data. */
    private fun trafficDelaySeconds(etaS: Double, remainingM: Double): Double? {
        val route = guidance.activeRoute() ?: return null
        val static = route.staticDurationSeconds
        if (!static.isFinite() || static <= 0.0 || route.totalDistanceMeters <= 0.0) return null
        val freeFlowLeft = static * (remainingM / route.totalDistanceMeters).coerceIn(0.0, 1.0)
        return (etaS - freeFlowLeft).takeIf { it > 0.0 }
    }

    /** Overview key: frame the rest of the route north-up, or back to following. */
    private fun toggleRouteOverview() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (cameraRig.mode == com.overdrive.app.navmap.motion.NavCameraRig.Mode.OVERVIEW) {
            recenterFollow(now)
            return
        }
        val pose = overviewPose() ?: return
        cameraRig.overview(now, currentCameraPose(), pose, RECENTER_ANIM_MS.toLong())
        guidance.activeRoute()?.let { showGlassRoute(it) }
        mainHandler.removeCallbacks(recenterAfterFree)
        hud?.setFree(false)
        hud?.setOverview(true)
        startMotionFollow()
    }

    private fun overviewPose(): com.overdrive.app.navmap.motion.NavCameraRig.Pose? {
        val mp = map ?: return null
        val route = guidance.activeRoute() ?: return null
        val track = motionTrack
        val s = motionModel.routeS
        val b = LatLngBounds.Builder()
        b.include(LatLng(motionModel.lat, motionModel.lng))
        var n = 1
        route.points.forEachIndexed { i, p ->
            if (track == null || s.isNaN() || track.sAtIndex(i) >= s) { b.include(LatLng(p.lat, p.lng)); n++ }
        }
        if (n < 2) return null
        val d = resources.displayMetrics.density
        // Keep the maneuver card (left), compass (top right) and trip bar (bottom) clear of the route.
        val pad = intArrayOf((500 * d).toInt(), (40 * d).toInt(), (110 * d).toInt(), (150 * d).toInt())
        val cam = try { mp.getCameraForLatLngBounds(b.build(), pad) } catch (_: Throwable) { null } ?: return null
        val t = cam.target ?: return null
        return com.overdrive.app.navmap.motion.NavCameraRig.Pose().apply {
            lat = t.latitude; lng = t.longitude; zoom = cam.zoom; tilt = 0.0; bearing = 0.0; paddingTop = 0.0
        }
    }

    /** Compass: heading-up ↔ north-up while following. */
    private fun toggleNorthUp() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (cameraRig.mode != com.overdrive.app.navmap.motion.NavCameraRig.Mode.FOLLOW) recenterFollow(now)
        cameraRig.setNorthUp(now, currentCameraPose(), !cameraRig.northUp, NORTH_UP_ANIM_MS)
        hud?.setCompass(if (lastCameraPose.lat.isNaN()) 0.0 else lastCameraPose.bearing, cameraRig.northUp)
        startMotionFollow()
    }

    /** The driver panned or zoomed during guidance: hand them the camera until they recentre or leave it alone. */
    private fun onUserMovedMap() {
        if (clusterMode && layoutGeneration > 0) {
            cameraRig.free(android.os.SystemClock.elapsedRealtime())
            mainHandler.removeCallbacks(recenterAfterFree)
            mainHandler.postDelayed(recenterAfterFree, com.overdrive.app.navmap.motion.NavCameraRig.RECENTER_AFTER_MS)
            return
        }
        // idle: the driver took the map
        if (!navigating && !clusterMode) idleFollowCamera = false
        if (!navigating || clusterMode) return
        cameraRig.free(android.os.SystemClock.elapsedRealtime())
        val h = hud
        if (h != null) { h.setFree(true); h.setOverview(false) } else findViewById<View>(R.id.fabLocate)?.visibility = View.VISIBLE
        mainHandler.removeCallbacks(recenterAfterFree)
        mainHandler.postDelayed(recenterAfterFree, com.overdrive.app.navmap.motion.NavCameraRig.RECENTER_AFTER_MS)
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
                // (a bearing only while moving: a crawl's is noise)
                val state = guidance.update(
                    fix.lat, fix.lng, fix.bearing?.takeIf { fix.bestSpeedMps > 2.0 }, fix.bestSpeedMps,
                    android.os.SystemClock.elapsedRealtime()
                )
                lastFix = fix
                lastRemainingM = state.remainingDistanceM
                val gear = gearNow(fix)
                noteTrack(fix, gear)
                if (!clusterMode) {
                    // back after a long while (parked, the map closed), standing at the destination of a trip that was
                    // under way: it arrived meanwhile
                    val gap = android.os.SystemClock.elapsedRealtime() - NavSession.headUnitTickAt
                    // (on the time the head unit slept since the last tick, whatever the first fix after it says: the
                    // car may be moving off already; a gap in fixes while awake isn't sleep)
                    val slept = if (NavSession.headUnitTickAt > 0L) sleptSinceTick() else 0L
                    if (settleLeftGuidance(slept)) return@post
                    // (asleep so long the trip is done with, wherever the car was left)
                    if (slept > STALE_TRIP_MS) {
                        endTrip()
                        return@post
                    }
                    markTick()
                    if (NavSession.headUnitTickAt > 0L && gap > STALE_GUIDANCE_GAP_MS && guidance.hasSetOut &&
                        clearOfLastStop() &&
                        routeStops.size <= 1 && fix.bestSpeedMps <= NEAR_STOP_SPEED_MPS && tripDest() != null &&
                        guidance.haversineMeters(fix.lat, fix.lng, tripDest()!!.lat, tripDest()!!.lng) <= STALE_ARRIVED_M
                    ) {
                        NavSession.guidanceTick(headUnit = true)
                        endTrip()
                        return@post
                    }
                    updateStops(fix, gear)
                    if (!recomputing && routeOutOfStep()) {
                        maybeReroute(fix.lat, fix.lng, fix.bearing?.takeIf { fix.bestSpeedMps > 2.0 }, quiet = true)
                    }
                    if (!persistedSetOut && guidance.hasSetOut && routeStops.lastOrNull() == guidedDest) persistTrip()
                    // behind another app, where the car is left if the head unit sleeps next; and the trip saved with it
                    // where the car is left if guidance stops ticking now (the screen off, the head unit asleep, the power
                    // gone), saved with the trip whenever it changes
                    val noted = Triple(leftAtDest, leftAtStop != null, leftStanding)
                    noteLeftAt(fix)
                    val noting = Triple(leftAtDest, leftAtStop != null, leftStanding)
                    val touchedAgoMs = android.os.SystemClock.elapsedRealtime() - tripTouchedAt
                    if ((noting != noted && touchedAgoMs >= TRIP_TOUCH_MIN_MS) || tripTouchedAt == 0L || touchedAgoMs >= TRIP_TOUCH_MS) {
                        touchTrip(leftAtDest, leftAtStop != null, leftStanding)
                    }
                    // (the drawing that measures traffic progress is off then)
                    if (mapStopped && guidance.progressM >= 0.0) trafficTraveledM = guidance.progressM
                }
                NavSession.guidanceTick(headUnit = !clusterMode)
                // Feed the TRUTH fix into the motion estimator. The puck + camera are
                // NOT painted here anymore — the ~12fps renderMotionFrame() glides
                // them from the dead-reckoned estimate so motion is continuous between
                // these sparse 1s fixes (was: teleport per fix). startMotionFollow()
                // is idempotent; ensure it's running.
                feedMotionTruth(fix)
                if (!mapStopped) startMotionFollow()
                // Arrival: the engine's radius/remainder check, OR a speed-gated
                // near-stop on the final approach. The latter handles the common
                // head-unit case where GPS parks the car 20-30 m off the true pin
                // (just outside ARRIVAL_RADIUS_M): if we're within the final band AND
                // the car has slowed to a stop, the driver HAS arrived — end nav
                // instead of letting an off-route wander hunt a reroute around the block.
                // Require TWO consecutive near-stop ticks so a car briefly halted at a
                // light/queue that happens to sit just short of the pin doesn't end nav
                // on the very first stopped fix.
                // under way once part of a route to this destination is behind the car (not at a round trip's start)
                val setOut = guidance.hasSetOut
                val offRoad = guidance.offRouteM >= NEAR_STOP_OFF_ROAD_M
                // (the gear known and not P)
                val inGear = gear != null && gear != GEAR_PARK
                // (on the road as well once the car stands right at the pin: a light that close is at the destination)
                if (state.remainingDistanceM <= NEAR_STOP_ARRIVAL_M && fix.bestSpeedMps <= NEAR_STOP_SPEED_MPS &&
                    ((offRoad && puckReachedDestination(NEAR_STOP_PIN_M)) || puckReachedDestination(AT_PIN_STOP_M))
                ) {
                    nearStopTicks++
                } else {
                    nearStopTicks = 0
                }
                val nearStopArrived = nearStopTicks >= NEAR_STOP_ARRIVAL_TICKS
                // PUCK-PROXIMITY arrival: the estimator pose (fed by the low-latency direct fix) against the LOCKED
                // destination, for when the route's end isn't where the car stops — parked across a divided road from it,
                // or by a pin the route can't reach (whatever stop was skipped on the way). It takes a long stop there:
                // a brief halt beside the pin isn't arriving. Head-unit only (the cluster has no locked dest).
                // Switched off within 150 m of it; in P for 3 s off the road within 150 m (its car park), or on it within
                // 60 m (a covered car park holds the fix at its entrance, an aisle the route runs through); little route
                // left either way. Else near
                // the pin for 150 s (longer than a light), 3 min with a stop still left (one skipped on the way); with
                // none left, off the road throughout within 60 m for 60 s (its car park; a forecourt further back
                // isn't), or standing within 150 m for 5 min (a kerb down the road, an aisle the route runs through), or,
                // not in gear, within 400 m for 10 min (a big car park). None where the car stands at a
                // stop just done with, nor with a long way round left (across a motorway, a river).
                val nowMono = android.os.SystemClock.elapsedRealtime()
                val stopped = fix.bestSpeedMps <= NEAR_STOP_SPEED_MPS
                val clear = clearOfLastStop()
                // (with a stop still left, not while at that stop: its own rules)
                val atNextStop = routeStops.size > 1 && guidance.haversineMeters(fix.lat, fix.lng, routeStops[0].lat, routeStops[0].lng) <=
                    TripStops.VISIT_NEAR_M
                val atPin = clear && stopped && !atNextStop && puckReachedDestination(LONG_STOP_RADIUS_M)
                // stood on the road short of it, then back or off the route from there (a drop-off at the school gate)
                val droppedOff = routeStops.size <= 1 && tripDest()?.let {
                    tripStops.destinationTick(it.lat, it.lng, fix.lat, fix.lng, fix.bestSpeedMps, nowMono)
                } == true && clear
                val nearOff = clear && stopped && offRoad && routeStops.size <= 1 && puckReachedDestination(NEAR_OFF_ROAD_RADIUS_M)
                // (with much of the route still to go — the near side of a divided road — only the longer waits count;
                // the gear known, standing with more than a little left is a queue, or a light waited out in P)
                val routeLong = state.remainingDistanceM > if (gear != null) PIN_ROUTE_LEFT_IN_GEAR_M else PIN_ROUTE_LEFT_M
                val nearLong = clear && stopped && routeStops.size <= 1 && !routeLong && puckReachedDestination(NEAR_LONG_RADIUS_M)
                val parkedNear = clear && stopped && !inGear && state.remainingDistanceM <= LEFT_ROUTE_MAX_M &&
                    routeStops.size <= 1 && puckReachedDestination(LEFT_AT_DEST_M)
                val inParkMs = if (inParkSinceMs > 0L) nowMono - inParkSinceMs else -1L
                // (in P off the road a few seconds running, not one fix wandering off a light's lane)
                if (inParkMs < 0L || !offRoad) parkedOffSinceMs = 0L else if (parkedOffSinceMs == 0L) parkedOffSinceMs = nowMono
                val parkedOff = parkedOffSinceMs > 0L && nowMono - parkedOffSinceMs >= PARKED_BY_MS &&
                    puckReachedDestination(NEAR_LONG_RADIUS_M)
                val parkedAtPin = inParkMs >= PARKED_BY_MS && puckReachedDestination(LONG_STOP_RADIUS_M)
                val parkedBy = clear && stopped && !routeLong && routeStops.size <= 1 && (parkedOff || parkedAtPin)
                // (switched off across a divided road from it is there: only a long way round by road isn't)
                val switchedOffBy = clear && stopped && !inGear && state.remainingDistanceM <= LEFT_ROUTE_MAX_M &&
                    switchedOff() && routeStops.size <= 1 && puckReachedDestination(NEAR_LONG_RADIUS_M)
                if (!atPin) atPinSinceMs = 0L else if (atPinSinceMs == 0L) atPinSinceMs = nowMono
                if (!nearOff) nearOffSinceMs = 0L else if (nearOffSinceMs == 0L) nearOffSinceMs = nowMono
                if (!nearLong) nearLongSinceMs = 0L else if (nearLongSinceMs == 0L) nearLongSinceMs = nowMono
                if (!parkedNear) parkedNearSinceMs = 0L else if (parkedNearSinceMs == 0L) parkedNearSinceMs = nowMono
                val atPinMs = when {
                    routeStops.size > 1 -> PARKED_NEAR_MS
                    routeLong -> NEAR_LONG_MS
                    else -> ON_ROAD_AT_PIN_MS
                }
                val longStopAtPin = (atPinSinceMs > 0L && nowMono - atPinSinceMs >= atPinMs) ||
                    (nearOffSinceMs > 0L && nowMono - nearOffSinceMs >= LONG_STOP_MS) ||
                    (nearLongSinceMs > 0L && nowMono - nearLongSinceMs >= NEAR_LONG_MS) ||
                    (parkedNearSinceMs > 0L && nowMono - parkedNearSinceMs >= PARKED_FAR_MS) ||
                    parkedBy || switchedOffBy || droppedOff
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
                    // at the end of the route (every stop on it passed in order; not a route out of step with the
                    // trip, as to an old destination), or parked at the trip's destination
                    if (setOut && (((state.arrived || nearStopArrived) && !routeOutOfStep()) || longStopAtPin)) {
                        speakOnce(getString(R.string.roadsense_map_arrived))
                        showSnackbar(getString(R.string.roadsense_map_arrived))
                        endTrip()
                        return@post
                    }
                    if (state.offRoute) {
                        com.overdrive.app.navmap.cluster.ClusterGuidanceClient.clear("roadsense")
                        // Driver diverged → auto-recompute a route to the LOCKED
                        // destination from the current fix, then keep navigating. Pass
                        // the heading only when genuinely moving (a stationary GPS
                        // heading is noise and would lock the reroute to a wrong way).
                        val rerouteHeading = fix.bearing?.takeIf { fix.bestSpeedMps > 2.0 }
                        maybeReroute(fix.lat, fix.lng, rerouteHeading)
                        return@post
                    }
                }
                bindHudGuidance(state)
                if (!clusterMode) {
                    com.overdrive.app.navmap.cluster.ClusterGuidanceClient.publishRoadSense(
                        state, displayEtaSeconds(state.etaSeconds))
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
                        "${formatDistance(state.remainingDistanceM)} • ${formatEta(displayEtaSeconds(state.etaSeconds))}")
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
    private fun puckReachedDestination(radiusM: Double = com.overdrive.app.navmap.nav.NavGuidanceEngine.ARRIVAL_RADIUS_M): Boolean =
        puckDestinationM().let { !it.isNaN() && it <= radiusM }

    // How far the car (the estimator's pose) is from the trip's destination; NaN when unknown.
    private fun puckDestinationM(): Double {
        val dest = tripDest() ?: return Double.NaN
        if (!motionEstimator.hasFix()) return Double.NaN
        val nowMono = android.os.SystemClock.elapsedRealtime()
        val m = motionEstimator.estimate(nowMono, nowMono) ?: return Double.NaN
        return guidance.haversineMeters(m.lat, m.lng, dest.lat, dest.lng)
    }

    // The trip's destination as it is now (the locked one only where there's no itinerary, as on the cluster).
    private fun tripDest(): com.overdrive.app.navmap.nav.GeoPoint? = routeStops.lastOrNull()
        ?.let { com.overdrive.app.navmap.nav.GeoPoint(it.lat, it.lng) }
        ?: if (lockedDestLat.isNaN()) null else com.overdrive.app.navmap.nav.GeoPoint(lockedDestLat, lockedDestLng)

    // The route on screen isn't the trip's: to another destination, through other stops or another order (an edit), or
    // through one the trip has left (a replan that hasn't landed).
    private fun routeOutOfStep(): Boolean =
        navigating && !clusterMode && routeStops.isNotEmpty() &&
            (replanPending || routeStops.last() != guidedDest || !tripStops.inStep(viaStops()))

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
                brakePercent = lastFix?.brakePercent,
                measuredAtMono = captureMono
            )
            recenterIfPending(loc.latitude, loc.longitude)
            startMotionFollow()   // idempotent — ensure the glide tick is running
        }
    }

    /** elapsedRealtime (ms) capture-instant of the last fix fed via the DIRECT source,
     *  for de-dup. Distinct from [lastFedFixTsMs] (the daemon wall-clock dedup). */
    @Volatile private var lastFedFixCaptureMonoMs: Long = 0L
    /** Capture instant (elapsedRealtime ms) the dead-reckon clock anchors to. Set by
     *  the direct source ([onDirectFix]); the render tick extrapolates from it. */
    @Volatile private var lastFixCaptureMonoMs: Long = 0L

    /** One vsync frame: pose from the estimate, then puck, traveled-route trim and camera. False = nothing left to animate. */
    private fun renderMotionFrame(nowMono: Long): Boolean {
        if (isFinishing || isDestroyed || !motionFollowActive) return false
        // Truth stalled near the dead-reckon cap: hold the last real position instead of freezing at the cap.
        if (lastFixCaptureMonoMs > 0L && (nowMono - lastFixCaptureMonoMs) >= KEEP_WARM_BEFORE_CAP_MS) {
            reanchorToLastFix(nowMono)
        }
        val m = motionEstimator.estimate(nowMono, nowMono)
        if (m == null) {
            motionFollowActive = false
            capRenderRate(0)
            return false
        }
        trackFor(if (navigating) guidance.activeRoute() else null)
        motionModel.update(
            nowMono, m.lat, m.lng, m.speedMps, m.bearingDeg, lastAcceptedFixMonoMs,
            if (navigating) routeSnap else null
        )
        if (navigating && motionModel.onRoute) {
            trafficTraveledM = motionModel.routeS
            glassLayers.setProgress(motionModel.routeS)
        }
        lastBearing = motionModel.puckBearing
        updateLocationPuckAt(motionModel.lat, motionModel.lng, motionModel.puckBearing)
        // Puck only while idle-browsing on the head unit: the camera stays where the user put it.
        val cameraMoving = (navigating || clusterMode) && driveCamera(nowMono)
        hud?.let { h ->
            if (!lastCameraPose.lat.isNaN() && cameraRig.mode != com.overdrive.app.navmap.motion.NavCameraRig.Mode.FREE) {
                h.setCompass(lastCameraPose.bearing, cameraRig.northUp)
            }
            if (navigating && nowMono - hudTickMs >= HUD_TICK_MS) {
                hudTickMs = nowMono
                val s = motionModel.routeS
                if (!s.isNaN() && !hudManeuverS.isNaN()) h.bindDistance((hudManeuverS - s).coerceAtLeast(0.0))
                h.bindSpeed(motionModel.speedMps)
                if (cameraRig.mode == com.overdrive.app.navmap.motion.NavCameraRig.Mode.FREE) {
                    h.setFreeProgress(cameraRig.freeProgress(nowMono))
                    map?.cameraPosition?.bearing?.let { h.setCompass(it, cameraRig.northUp) }
                }
            }
        }
        // Head unit while the cluster mirrors this drive: the driver watches the cluster, so tick slowly.
        val limit = if (projectionActive) PROJECTION_FRAME_DIVISOR else 0
        frameLoop.limitDivisor(limit)
        val keep = !motionModel.settled || cameraMoving
        capRenderRate(if (keep) 60 / maxOf(frameLoop.divisor, limit) else 0)
        perfMeter.summary(nowMono, navigating || clusterMode, maxOf(frameLoop.divisor, limit), glassLowTier)
            ?.let { Log.i("NavPerf", it) }
        if (!keep) motionFollowActive = false
        return keep
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
    /** Since when the car has stood within [LONG_STOP_RADIUS_M] / [LEFT_AT_DEST_M] of the destination (0: not). */
    private var atPinSinceMs = 0L
    private var nearOffSinceMs = 0L
    private var nearLongSinceMs = 0L
    private var parkedNearSinceMs = 0L
    // The gear as last read (null: unknown), when, and since when it has been P (0: not).
    private var gearRead: String? = null
    private var gearReadAt = 0L
    private var gearTickAt = 0L
    private var inParkSinceMs = 0L
    private var parkedOffSinceMs = 0L
    // since when the car has been switched off (0: not), where, and whether a reading of it since went stale
    private var switchedOffSinceMs = 0L
    private var switchedOffLat = Double.NaN
    private var switchedOffLng = Double.NaN
    private var switchedOffStale = false
    // a restored trip's last stop was done with with the car here
    private var resumeStopLeftAt: com.overdrive.app.navmap.nav.GeoPoint? = null
    private val tripStops by lazy { TripStops(guidance) }
    /** When the saved trip was last stamped as guiding. */
    private var tripTouchedAt = 0L


    /**
     * Auto-reroute: when the engine reports off-route, recompute a fresh route
     * from the current position to the LOCKED destination and resume guidance on
     * it. Debounced (REROUTE_MIN_INTERVAL_MS) and single-flight (`rerouting`) so a
     * burst of off-route ticks fires at most one recompute. No-op if the
     * destination isn't set (shouldn't happen while navigating).
     */
    private fun maybeReroute(
        fromLat: Double, fromLng: Double, fromHeading: Double? = null, force: Boolean = false, quiet: Boolean = false
    ) {
        // (a replan for changed stops waits for one in flight, then goes; it isn't held back by the debounce)
        if (rerouting) { if (force) rerouteAgain = true; return }
        if (lockedDestLat.isNaN() || lockedDestLng.isNaN()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastRerouteMs < REROUTE_MIN_INTERVAL_MS) return
        // (not for crawling off the road right by the destination: its car park, whose search would replace the route)
        // (the gear unknown: arriving there relies on the car being off the route)
        if (!force && !quiet && !gearKnown() && routeStops.size <= 1 && (lastFix?.bestSpeedMps ?: 0.0) <= CAR_PARK_MPS &&
            tripDest()?.let { guidance.haversineMeters(fromLat, fromLng, it.lat, it.lng) <= NO_REROUTE_NEAR_DEST_M } == true
        ) return
        lastRerouteMs = now
        rerouting = true
        val gen = rerouteGen
        // Itinerary-aware: build the list of stops still AHEAD of the car so a
        // multi-stop trip keeps its remaining via-stops on a reroute instead of
        // collapsing to a direct run at the final destination. Snapshot on the main
        // thread (routeStops is main-thread state).
        // (a stop just driven through, and turned off from right after, stays behind)
        tripStops.beforeReroute(viaStops(), fromLat, fromLng)?.let { dropDone(it) }
        val remainingStops = remainingStopsAhead()
        // to the trip's destination as it is now (a replaced one included), not the one the old route locked
        val target = remainingStops.lastOrNull() ?: run { rerouting = false; return }
        val dLat = target.lat; val dLng = target.lng
        // (a skip only when the car itself left the route: a replan of ours isn't the driver asking; nor over a way back
        // to a stop just passed)
        if (!quiet && now >= goBackUntil) { if (force) showSnackbar(getString(R.string.roadsense_map_rerouting)) else showRerouteSnackbar() }
        val heading = fromHeading ?: routeHeading()
        ioExecutor().execute {
            // Pass the travel heading so the new route departs in the direction the
            // car is already going (no demanded U-turn back to the old path).
            val routes: List<NavRoute> = if (remainingStops.size > 1) {
                // ≥1 via-stop still ahead → route THROUGH them in order. Valhalla
                // gives no alternates on multipoint, which is expected.
                val pts = ArrayList<com.overdrive.app.navmap.nav.GeoPoint>(remainingStops.size + 1)
                pts.add(com.overdrive.app.navmap.nav.GeoPoint(fromLat, fromLng))
                remainingStops.forEach { pts.add(com.overdrive.app.navmap.nav.GeoPoint(it.lat, it.lng)) }
                NavRouting.routeVia(pts, heading)
            } else {
                NavRouting.routesWithAlternates(
                    fromLat, fromLng, dLat, dLng, ROUTE_ALTERNATES, heading
                )
            }
            mainHandler.post {
                // (one for a trip that has since ended or restarted is nobody's)
                if (gen != rerouteGen) return@post
                rerouting = false
                if (isFinishing || isDestroyed || !navigating) return@post
                // the trip's stops changed meanwhile (one reached or visited): this route is stale, plan again
                if (rerouteAgain || routeStops != remainingStops) {
                    rerouteAgain = false
                    lastFix?.let { f -> maybeReroute(f.lat, f.lng, f.bearing?.takeIf { f.bestSpeedMps > 2.0 }, force = true) }
                    return@post
                }
                if (routes.isEmpty()) return@post // keep the old route; try again next divergence
                previewRoutes = routes
                previewSelectedIdx = 0
                lockedDestLat = dLat
                lockedDestLng = dLng
                previewDestLabel = target.label
                previewStops = remainingStops
                guidance.start(routes[0])
                guidedDest = target
                replanPending = false
                tripStops.routeInstalled(viaStops(remainingStops), fromLat, fromLng)
                resetRouteTrim()   // rerouted → reset trim against the NEW route arc length
                lastSpokenInstruction = null
                drawRoutePreview(routes, 0)
                // Propagate the NEW best route to the cluster mirror. Without this
                // the cluster keeps guiding on the STALE pre-divergence route after
                // the head unit reroutes (NavSession was only published at nav start).
                if (!clusterMode) NavSession.publishRoute(routes[0], previewDestLabel)
                onGuidanceRouteChanged(routes[0])
            }
        }
    }

    /**
     * The itinerary stops still ahead of the car, for an itinerary-aware reroute: [aheadStop1, …, destination]. Stops
     * the car is done with already left [routeStops] (see [updateStops]); one it's just missing the entrance of stays,
     * so the reroute goes back to it. Reads [routeStops] — call on the main thread.
     */
    private fun remainingStopsAhead(): List<SearchResult> = ArrayList(routeStops)

    // The trip's stops still to come before its destination, in order.
    private fun viaStops(stops: List<SearchResult> = routeStops): List<SearchResult> =
        if (stops.size >= 2) stops.subList(0, stops.size - 1) else emptyList()

    // Stops the car is done with leave the trip ([TripStops]: driven through, left behind, stopped at, dropped off at),
    // so no reroute, reopened map or restore sends it back to one. A visited one has the rest planned from here.
    private fun updateStops(fix: RoadSenseHazardApiClient.LatLngFix, gear: String?) {
        if (!navigating) return
        val done = tripStops.tick(
            viaStops(), fix.lat, fix.lng, fix.bestSpeedMps, android.os.SystemClock.elapsedRealtime(), gear == GEAR_PARK,
            switchedOff() && gear.let { it == null || it == GEAR_PARK }
        ) ?: return
        dropDone(done)
        if (done.reason == TripStops.Reason.VISITED) replanFrom(fix)
    }

    // Stops done with: dropped, one passed on the route said, with a way back should its entrance have been missed.
    private fun dropDone(done: TripStops.Done) {
        val passed = routeStops.getOrNull(done.count - 1)?.takeIf { done.reason == TripStops.Reason.REACHED && routeStops.size > done.count }
        // (one passed by still guards the destination: the car may be parking just past it)
        dropLeadingStops(done.count, stood = done.stood)
        passed?.let { offerGoBack(it) }
    }

    private fun offerGoBack(stop: SearchResult) {
        try { navVoice?.speakQueued(passedText(stop)) } catch (_: Throwable) {}
        if (clusterMode) return
        val dest = routeStops.lastOrNull()
        // (behind another app: offered when the map is back)
        if (mapStopped) {
            pendingGoBack = Triple(stop, dest, android.os.SystemClock.elapsedRealtime())
            return
        }
        showGoBack(stop, dest)
    }

    private fun passedText(stop: SearchResult): String =
        getString(R.string.roadsense_map_stop_passed, stop.label.substringBefore(',').trim().ifEmpty { stop.label })

    private fun showGoBack(stop: SearchResult, dest: SearchResult?) {
        val root = findViewById<View>(R.id.mapRoot) ?: return
        val bar = Snackbar.make(root, passedText(stop), SKIP_OFFER_SHOW_MS)
            .setAction(R.string.roadsense_map_go_back) { goBackTo(stop, dest) }
        findViewById<View>(R.id.fabEndNav)?.takeIf { it.visibility == View.VISIBLE }?.let { bar.anchorView = it }
        bar.show()
        goBackUntil = android.os.SystemClock.elapsedRealtime() + SKIP_OFFER_SHOW_MS
    }

    // A way back to a stop passed behind another app, and until when one is on screen.
    private var pendingGoBack: Triple<SearchResult, SearchResult?, Long>? = null
    private var goBackUntil = 0L

    // The driver wants the stop just passed after all (on the same trip): the next stop again, routed back to.
    private fun goBackTo(stop: SearchResult, dest: SearchResult?) {
        if (!navigating || clusterMode || routeStops.isEmpty() || routeStops.last() !== dest || routeStops.any { it === stop } ||
            editingStopIndex >= 0 || addingStop
        ) return
        goBackUntil = 0L
        routeStops.add(0, stop)
        // (the car wasn't at it)
        tripStops.forgetLeftStop()
        NavTripStore.clearStopLeft(applicationContext)
        persistTrip()
        rebuildStopsUi()
        lastFix?.let { replanFrom(it) }
    }

    private fun replanFrom(fix: RoadSenseHazardApiClient.LatLngFix) {
        replanPending = true
        maybeReroute(fix.lat, fix.lng, fix.bearing?.takeIf { fix.bestSpeedMps > 2.0 }, force = true)
    }

    /** A replan for changed stops hasn't landed yet (offline, say): the route on screen still has the old ones. */
    private var replanPending = false

    // The leading [n] stops leave the trip; a search still open for one keeps its aim.
    private fun dropLeadingStops(n: Int, atStop: Boolean = true, stood: Boolean = false) {
        val k = n.coerceAtMost(routeStops.size - 1)
        if (k <= 0) return
        // an offer computed with the old stops would route through the one that left
        dismissFasterRoutePrompt()
        val left = ArrayList(routeStops.subList(0, k))
        repeat(k) { routeStops.removeAt(0) }
        tripStops.dropped(left, atStop, stood)
        // editing a stop that just left: the pick becomes an added stop; a later one moved up
        if (editingStopIndex in 0 until k) {
            editingStopIndex = -1
            addingStop = true
        } else if (editingStopIndex >= k) {
            editingStopIndex -= k
        }
        persistTrip()
        if (atStop) NavTripStore.stopLeft(applicationContext, left.last().lat, left.last().lng)
        rebuildStopsUi()
        guidance.activeRoute()?.let { renderItineraryMarkers(it, routeStops) }
    }

    // How long guidance was away, should the app be restarted meanwhile.
    private fun touchTrip(leftAtDest: Boolean = false, leftAtStop: Boolean = false, standing: Boolean = false) {
        if (clusterMode || !navigating) return
        tripTouchedAt = android.os.SystemClock.elapsedRealtime()
        NavTripStore.touch(applicationContext, leftAtDest, leftAtStop, standing)
    }

    /** When the map last stopped while guiding: the car was by its destination / standing by this next stop; standing. */
    private var leftAtDest = false
    private var leftAtStop: SearchResult? = null
    private var leftStanding = false

    // Where the car is left if guidance stops ticking now (the map going away, the head unit about to sleep): by its
    // destination (no stop left, under way), or by its next stop (having driven on since the last).
    // (Standing right by it — the destination within 150 m, the next stop within 60 m — counts after the short wait;
    // anything else after the long one. Not for a route out of step: an edit's new stops aren't where the car was.)
    private fun noteLeftAt(here: RoadSenseHazardApiClient.LatLngFix?) {
        if (here == null || routeOutOfStep()) {
            clearLeftAt()
            return
        }
        val standing = here.bestSpeedMps <= TripStops.STANDING_MPS
        val destM = tripDest()?.let { guidance.haversineMeters(here.lat, here.lng, it.lat, it.lng) } ?: Double.MAX_VALUE
        // (not where a stop was just done with, nor with a long way round left)
        leftAtDest = routeStops.size <= 1 && guidance.hasSetOut && clearOfLastStop() && destM <= LEFT_AT_DEST_M &&
            lastRemainingM <= LEFT_ROUTE_MAX_M
        val next = routeStops.takeIf { it.size >= 2 }?.get(0)
        val stopM = next?.let { guidance.haversineMeters(here.lat, here.lng, it.lat, it.lng) } ?: Double.MAX_VALUE
        leftAtStop = next?.takeIf { tripStops.armedToReach && stopM <= LEFT_AT_STOP_M }
        // (in P or switched off by the next stop: parked for it, if not right at it)
        val parked = inParkSinceMs > 0L || switchedOffSinceMs > 0L
        leftStanding = (standing && ((leftAtDest && destM <= LEFT_NEAR_DEST_M) || (leftAtStop != null && stopM <= LEFT_NEAR_STOP_M))) ||
            (parked && leftAtStop != null)
    }

    private fun resetArrivalTimers() {
        nearStopTicks = 0
        parkedOffSinceMs = 0L
        atPinSinceMs = 0L
        nearOffSinceMs = 0L
        nearLongSinceMs = 0L
        parkedNearSinceMs = 0L
    }

    // The gear with this fix (null: unknown), a reading stale for a moment (DiLink5 between probes) kept a few seconds.
    private fun gearNow(fix: RoadSenseHazardApiClient.LatLngFix): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        // (a state before a gap in ticks isn't known to have lasted through it)
        if (now - gearTickAt > GEAR_HOLD_MS) { inParkSinceMs = 0L; switchedOffSinceMs = 0L }
        gearTickAt = now
        fix.gear?.let { gearRead = it; gearReadAt = now }
        val g = gearRead?.takeIf { now - gearReadAt <= GEAR_HOLD_MS }
        if (g != GEAR_PARK) inParkSinceMs = 0L else if (inParkSinceMs == 0L) inParkSinceMs = now
        when {
            fix.accOn != false -> { switchedOffSinceMs = 0L; switchedOffStale = false }
            switchedOffStale -> {}
            switchedOffSinceMs == 0L -> { switchedOffSinceMs = now; switchedOffLat = fix.lat; switchedOffLng = fix.lng }
            // (moved on since: running again, whatever the daemon still says)
            guidance.haversineMeters(fix.lat, fix.lng, switchedOffLat, switchedOffLng) > SWITCHED_OFF_MOVED_M -> {
                switchedOffSinceMs = 0L
                switchedOffStale = true
            }
        }
        return g
    }

    // Saved places and recent destinations, prefetched for offline use when the network is Wi-Fi (not the car's SIM, nor
    // a phone's hotspot).
    private var placesPrefetchQueued = false
    private val prefetchPlacesTask = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        val ctx = applicationContext
        val places = ArrayList<Pair<Double, Double>>()
        listOfNotNull(SavedPlacesStore.getHome(ctx), SavedPlacesStore.getWork(ctx)).forEach { places.add(Pair(it.lat, it.lng)) }
        SavedPlacesStore.getCustom(ctx).forEach { places.add(Pair(it.lat, it.lng)) }
        RecentSearchStore.getAll(ctx).forEach { places.add(Pair(it.lat, it.lng)) }
        val usable = places.filter { !it.first.isNaN() && !it.second.isNaN() }
        if (usable.isEmpty()) return@Runnable
        val density = resources.displayMetrics.density
        ioExecutor().execute {
            val route = com.overdrive.app.safekeep.util.NetRoute.probe()
            val unmetered = !route.metered() && !route.likelyPhoneHotspot
            mainHandler.post {
                if (!isFinishing && !isDestroyed) MapTilePrefetcher.prefetchPlaces(ctx, STYLE_URL, usable, density, unmetered)
            }
        }
    }

    // The way the car is going, for a route from where it is: the fix's bearing while moving, else the line of its track
    // over the last 25 m if that ran straight (a crawl in a queue; not a turn, a three-point turn); null when not known.
    private fun routeHeading(): Double? {
        // (backing up: the way it moves isn't the way it faces)
        if (gearKnown() && gearRead == GEAR_REVERSE) return null
        val f = lastFix ?: return null
        if (f.bestSpeedMps > 2.0) f.bearing?.let { return it }
        val now = android.os.SystemClock.elapsedRealtime()
        var pathM = 0.0
        var prevLat = f.lat
        var prevLng = f.lng
        for (j in 1..minOf(trackCount, TRACK_N)) {
            val i = (trackCount - j) % TRACK_N
            if (now - trackAt[i] > TRACK_MAX_AGE_MS) return null
            pathM += guidance.haversineMeters(prevLat, prevLng, trackLat[i], trackLng[i])
            prevLat = trackLat[i]
            prevLng = trackLng[i]
            if (guidance.haversineMeters(trackLat[i], trackLng[i], f.lat, f.lng) >= TRACK_HEADING_M) {
                val lineM = guidance.haversineMeters(trackLat[i], trackLng[i], f.lat, f.lng)
                if (lineM < TRACK_STRAIGHT * pathM) return null
                val y = Math.sin(Math.toRadians(f.lng - trackLng[i])) * Math.cos(Math.toRadians(f.lat))
                val x = Math.cos(Math.toRadians(trackLat[i])) * Math.sin(Math.toRadians(f.lat)) -
                    Math.sin(Math.toRadians(trackLat[i])) * Math.cos(Math.toRadians(f.lat)) * Math.cos(Math.toRadians(f.lng - trackLng[i]))
                return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
            }
        }
        return null
    }

    // Recent fixes while moving at all, for the line of the car's track.
    private fun noteTrack(fix: RoadSenseHazardApiClient.LatLngFix, gear: String?) {
        // (backing up isn't the way it will go: the line starts again after it)
        if (gear == GEAR_REVERSE) { trackCount = 0; return }
        if (fix.bestSpeedMps < TRACK_MIN_MPS) return
        val i = trackCount % TRACK_N
        trackLat[i] = fix.lat
        trackLng[i] = fix.lng
        trackAt[i] = android.os.SystemClock.elapsedRealtime()
        trackCount++
    }
    private val trackLat = DoubleArray(TRACK_N)
    private val trackLng = DoubleArray(TRACK_N)
    private val trackAt = LongArray(TRACK_N)
    private var trackCount = 0
    // the route still to go at the last tick
    private var lastRemainingM = Double.MAX_VALUE

    // Switched off a few seconds, where the car still is.
    // Reported off, and not moved since. No hold: a DiLink 3.0 head unit stops the app ~2.5 s after the car goes off.
    private fun switchedOff(): Boolean = switchedOffSinceMs > 0L

    // Clear of the last stop the car was at (nearer the destination than it), or none.
    private fun clearOfLastStop(): Boolean = tripDest()?.let { tripStops.clearOfLastStop(it.lat, it.lng) } ?: true

    private fun gearKnown(): Boolean = gearRead != null && android.os.SystemClock.elapsedRealtime() - gearReadAt <= GEAR_HOLD_MS

    private fun clearLeftAt() {
        leftAtDest = false
        leftAtStop = null
        leftStanding = false
    }

    // Guidance couldn't tick for [awayMs] (the head unit asleep) with the car left by its destination (switched off
    // there, parked): it arrived; by its next stop: visited (the rest is planned from where the car is now). A car left
    // standing counts after a short while, one still moving (a frozen fix underground) after a long one. True when the
    // trip ended.
    private fun settleLeftGuidance(awayMs: Long): Boolean {
        if (!navigating || clusterMode || (!leftAtDest && leftAtStop == null)) return false
        val waitMs = if (leftStanding) LEFT_PARKED_RETURN_MS else STALE_GUIDANCE_GAP_MS
        if (awayMs < waitMs) return false
        val dest = leftAtDest
        val stop = leftAtStop
        clearLeftAt()
        markTick()
        if (dest) {
            endTrip()
            return true
        }
        if (stop != null && routeStops.size >= 2 && routeStops[0] === stop) {
            replanPending = true
            dropLeadingStops(1, stood = true)
        } else {
            touchTrip()
        }
        return false
    }

    // The head unit's clocks at the last guidance tick: time asleep since is the wall time less the awake time.
    private var tickElapsedAt = 0L
    private var tickUptimeAt = 0L

    private fun markTick() {
        tickElapsedAt = android.os.SystemClock.elapsedRealtime()
        tickUptimeAt = android.os.SystemClock.uptimeMillis()
    }

    private fun sleptSinceTick(): Long =
        if (tickElapsedAt == 0L) 0L
        else ((android.os.SystemClock.elapsedRealtime() - tickElapsedAt) - (android.os.SystemClock.uptimeMillis() - tickUptimeAt))
            .coerceAtLeast(0L)

    // "Recalculating"; with a stop still to come close by (a big site parked far from its pin, a drop-off down the
    // road), one tap skips it.
    private fun showRerouteSnackbar() {
        if (mapStopped) return
        val next = if (routeStops.size >= 2) routeStops[0] else null
        val here = lastFix
        if (next == null || here == null || clusterMode || (!tripStops.nearNext(SKIP_OFFER_M) &&
                guidance.haversineMeters(here.lat, here.lng, next.lat, next.lng) > SKIP_OFFER_M)
        ) {
            showSnackbar(getString(R.string.roadsense_map_rerouting))
            return
        }
        val root = findViewById<View>(R.id.mapRoot) ?: return
        val bar = Snackbar.make(root, getString(R.string.roadsense_map_rerouting), SKIP_OFFER_SHOW_MS)
            .setAction(R.string.roadsense_map_skip_stop) { skipStop(next) }
        findViewById<View>(R.id.fabEndNav)?.takeIf { it.visibility == View.VISIBLE }?.let { bar.anchorView = it }
        bar.show()
    }

    // The driver skipped the next stop (if it still is): on to the rest of the trip.
    private fun skipStop(stop: SearchResult) {
        if (!navigating || routeStops.size < 2 || routeStops[0] !== stop) return
        dropLeadingStops(1, atStop = false)
        lastFix?.let { replanFrom(it) }
    }

    // ---------------------------------------------------------------------
    // Live traffic (Google routes): congestion overlay, refresh, ETA, faster route
    // ---------------------------------------------------------------------

    /** Shows [spans] on [route] (null clears) and remembers them for a style reload; empty→empty is a no-op. */
    private fun showTraffic(route: NavRoute?, spans: List<CongestionSpan>) {
        val stillEmpty = spans.isEmpty() && displayedTrafficSpans.isEmpty()
        displayedTrafficRoute = route
        displayedTrafficSpans = spans
        if (!stillEmpty) glassLayers.setCongestion(route, spans)
    }

    /** Shows the newest congestion for [route]: its live refresh when there is one, else its own spans. */
    private fun showRouteTraffic(route: NavRoute) {
        showTraffic(route, NavSession.traffic?.takeIf { it.route === route }?.spans ?: route.congestion)
    }

    /** The route guidance is running: the cluster's mirrored route, else the selected preview entry. */
    private fun currentGuidanceRoute(): NavRoute? = when {
        !navigating -> null
        clusterMode -> clusterNavRoute
        else -> previewRoutes.getOrNull(previewSelectedIdx)
    }

    /** Re-arms live-traffic state after guidance moves to [route]; the refresh runs on the head unit only. */
    private fun onGuidanceRouteChanged(route: NavRoute) {
        clearTrafficEta()
        dismissFasterRoutePrompt()
        if (clusterMode) return
        if (route.provider != RouteProvider.GOOGLE) { stopTrafficRefresh(); return }
        val running = trafficRefresh
        if (running != null) running.onRouteReplaced()
        else trafficRefresh = TrafficRefreshController(trafficHost).also { it.start() }
    }

    private fun stopTrafficRefresh() {
        trafficRefresh?.stop()
        trafficRefresh = null
    }

    /** Drops all live-traffic state when guidance ends. */
    private fun endLiveTraffic() {
        stopTrafficRefresh()
        clearTrafficEta()
        dismissFasterRoutePrompt()
        trafficTraveledM = 0.0
        showTraffic(null, emptyList())
    }

    private fun setTrafficEta(route: NavRoute, etaS: Double, atMs: Long = android.os.SystemClock.elapsedRealtime()) {
        if (!etaS.isFinite() || etaS < 0.0) return
        trafficEtaRoute = route
        trafficEtaS = etaS
        trafficEtaAtMs = atMs
    }

    private fun clearTrafficEta() {
        trafficEtaRoute = null
        trafficEtaS = Double.NaN
        trafficEtaAtMs = 0L
    }

    /** The traffic ETA counted down since its refresh while fresh and for the guided route, else [engineEtaS]. */
    private fun displayEtaSeconds(engineEtaS: Double): Double {
        val route = trafficEtaRoute ?: return engineEtaS
        val ageMs = android.os.SystemClock.elapsedRealtime() - trafficEtaAtMs
        if (route !== currentGuidanceRoute() || ageMs >= TRAFFIC_ETA_MAX_AGE_MS) return engineEtaS
        return (trafficEtaS - ageMs / 1000.0).coerceAtLeast(0.0)
    }

    /** Stops still ahead for a traffic refresh: every one left in the trip (done ones have left it), destination last. */
    private fun trafficWaypoints(): List<GeoPoint> = remainingStopsAhead().map { GeoPoint(it.lat, it.lng) }
    /** The waypoints the latest traffic refresh asked for. */
    private var trafficAskedWaypoints: List<GeoPoint> = emptyList()

    private val trafficHost = object : TrafficRefreshController.Host {
        override fun mainHandler(): Handler = this@RoadSenseMapActivity.mainHandler
        override fun isNavigating(): Boolean = navigating
        override fun currentRoute(): NavRoute? = currentGuidanceRoute()
        override fun currentFix(): GeoPoint? = lastFix?.let { GeoPoint(it.lat, it.lng) }
        // Heading only while moving, as for a reroute (a parked heading is noise).
        override fun currentHeading(): Double? = lastFix?.let { f -> f.bearing?.takeIf { f.bestSpeedMps > 2.0 } }
        override fun currentSpeedMps(): Double? = lastFix?.bestSpeedMps?.takeIf { it.isFinite() && it >= 0.0 }
        override fun remainingWaypoints(): List<GeoPoint> {
            if (lastFix == null) return emptyList()
            return trafficWaypoints().also { trafficAskedWaypoints = it }
        }
        override fun traveledMeters(): Double = trafficTraveledM
        override fun onTrafficUpdate(route: NavRoute, spans: List<CongestionSpan>, remainingEtaSeconds: Double) =
            onLiveTraffic(route, spans, remainingEtaSeconds)
        override fun onFasterRoute(candidate: NavRoute, savesSeconds: Double) =
            showFasterRoutePrompt(candidate, savesSeconds)
    }

    /** Head unit: a refresh for the guided route updates the overlay + ETA and reaches the cluster via publishTraffic. */
    private fun onLiveTraffic(route: NavRoute, spans: List<CongestionSpan>, etaS: Double) {
        if (isFinishing || isDestroyed || clusterMode || route !== currentGuidanceRoute()) return
        showTraffic(route, spans)
        setTrafficEta(route, etaS)
        NavSession.publishTraffic(route, spans, etaS)
    }

    /** Cluster: applies a head-unit refresh for the route shown here; a replayed refresh keeps its first ETA stamp. */
    private fun onClusterTraffic(t: NavSession.Traffic) {
        if (isFinishing || isDestroyed) return
        if (t.route !== clusterNavRoute && t.route !== displayedTrafficRoute) return
        showTraffic(t.route, t.spans)
        if (t !== lastClusterTraffic) {
            lastClusterTraffic = t
            setTrafficEta(t.route, t.remainingEtaSeconds, t.atMs)
        }
    }

    /** Offers a faster route while navigating; auto-dismissed after [FASTER_ROUTE_PROMPT_MS]. */
    private fun showFasterRoutePrompt(candidate: NavRoute, savesSeconds: Double) {
        if (isFinishing || isDestroyed || clusterMode) return
        // (planned through stops the trip has since left)
        if (trafficAskedWaypoints != trafficWaypoints()) return
        val base = currentGuidanceRoute() ?: return
        val root = findViewById<View>(R.id.mapRoot) ?: return
        dismissFasterRoutePrompt()
        val bar = Snackbar.make(
            root, getString(R.string.navmap_google_faster_route, formatEta(savesSeconds)), Snackbar.LENGTH_INDEFINITE
        ).setAction(R.string.navmap_google_faster_route_switch) { switchToFasterRoute(candidate, base) }
        findViewById<View>(R.id.fabEndNav)?.takeIf { it.visibility == View.VISIBLE }?.let { bar.anchorView = it }
        fasterRouteBar = bar
        bar.show()
        mainHandler.postDelayed(dismissFasterRoute, FASTER_ROUTE_PROMPT_MS)
    }

    private fun dismissFasterRoutePrompt() {
        mainHandler.removeCallbacks(dismissFasterRoute)
        fasterRouteBar?.dismiss()
        fasterRouteBar = null
    }

    /** Moves guidance to [candidate] (same steps as a reroute) if the car is still on [base]. */
    private fun switchToFasterRoute(candidate: NavRoute, base: NavRoute) {
        mainHandler.removeCallbacks(dismissFasterRoute)
        fasterRouteBar = null
        if (isFinishing || isDestroyed || clusterMode || currentGuidanceRoute() !== base) return
        previewRoutes = (listOf(candidate, base) + previewRoutes.filter { it !== base && it !== candidate })
            .take(ROUTE_ALTERNATES + 1)
        previewSelectedIdx = 0
        guidance.start(candidate)
        tripStops.routeSwitched(lastFix?.lat ?: Double.NaN, lastFix?.lng ?: Double.NaN)
        resetRouteTrim()
        lastSpokenInstruction = null
        drawRoutePreview(previewRoutes, 0)
        NavSession.publishRoute(candidate, previewDestLabel)
        onGuidanceRouteChanged(candidate)
    }

    private fun stopGuidance(keepSaved: Boolean = false) {
        navigating = false
        cancelRouteRetry()
        // Nav ended (arrived / end-nav) → drop the persisted trip so a later re-entry
        // opens fresh, not back into a finished route.
        if (!clusterMode && !keepSaved) NavTripStore.clear(applicationContext)
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
        motionModel.reset()             // next trip seeds the shown pose fresh
        cameraRig.reset()
        motionTrack = null
        mainHandler.removeCallbacks(recenterAfterFree)
        puckPaintedLat = Double.NaN     // reset painted-pose dead-band so next trip re-seeds
        puckPaintedLng = Double.NaN
        puckPaintedBearing = Double.NaN
        lastCameraPose.lat = Double.NaN // reset the camera dead-band baseline too
        lastFedFixTsMs = 0L             // reset the duplicate-fix dedup for the next trip
        nearStopTicks = 0               // reset the near-stop arrival debounce
        parkedOffSinceMs = 0L
        goBackUntil = 0L
        pendingGoBack = null
        atPinSinceMs = 0L
        nearOffSinceMs = 0L
        nearLongSinceMs = 0L
        parkedNearSinceMs = 0L
        tripStops.begin()
        rerouteGen++
        rerouting = false
        rerouteAgain = false
        replanPending = false
        clearLeftAt()
        tickElapsedAt = 0L
        tickUptimeAt = 0L
        tripTouchedAt = 0L
        endLiveTraffic()                // stop the traffic refresh + clear congestion/ETA
        glassLayers.clear()
        findViewById<View>(R.id.navBanner)?.visibility = View.GONE
        findViewById<View>(R.id.navBannerIcon)?.visibility = View.GONE
        showNavHud(false)
        findViewById<FloatingActionButton>(R.id.fabEndNav)?.visibility = View.GONE
        exitImmersive()
        // Clear the navigation POIs (they were route-specific).
        poiSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
        // Hand the puck back to the idle follow so it keeps tracking the live fix after
        // the route ends (head unit → idle follow; cluster → its own idle follow).
        // The camera follows the car again too (guidance's did until now).
        idleFollowCamera = true
        guidedDest = null
        // (behind another app the idle follow waits for onResume: nothing to draw)
        if (clusterMode) startClusterFollow() else if (!mapStopped) startIdleFollow()
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
     * Map a Valhalla `DirectionsLeg.Maneuver.Type` to a turn glyph: 4-6 destination,
     * 9/10/11 slight/turn/sharp right, 12/13 u-turn right/left, 14/15/16 sharp/turn/slight
     * left, 17-21 ramp/exit, 22-24 stay, 25/37/38 merge, 26/27 roundabout. 0 = no glyph.
     */
    private fun maneuverIconRes(type: Int): Int = when (type) {
        4, 5, 6 -> R.drawable.ic_man_arrive
        9 -> R.drawable.ic_man_slight_right
        10 -> R.drawable.ic_man_turn_right
        11 -> R.drawable.ic_man_sharp_right
        12, 13 -> R.drawable.ic_man_uturn              // u-turn right/left
        14 -> R.drawable.ic_man_sharp_left
        15 -> R.drawable.ic_man_turn_left
        16 -> R.drawable.ic_man_slight_left
        17, 18, 19 -> R.drawable.ic_man_ramp           // ramp straight/right/left
        20, 21 -> R.drawable.ic_man_ramp               // exit right/left
        22 -> R.drawable.ic_man_straight               // stay/continue on highway
        23 -> R.drawable.ic_man_slight_right           // keep right
        24 -> R.drawable.ic_man_slight_left            // keep left
        25 -> R.drawable.ic_man_merge
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
        idleFollowCamera = true
        ioExecutor().execute {
            val fix = RoadSenseHazardApiClient.fetchCurrentLocation()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (fix != null) {
                    lastFix = fix
                    recenterPending = false
                    // Center on the live fix at street zoom with a gentle north-up
                    // RESTING TILT so the idle map reads as a modern 3D perspective
                    // (buildings/road casings gain depth) instead of a flat top-down
                    // sheet. North-up (bearing 0) keeps it a calm "where am I" view —
                    // heading-up + steep tilt is reserved for active guidance. The tilt
                    // is suppressed on the non-touch cluster (its follow loop owns the
                    // camera) and while navigating (guidance owns it).
                    centreOn(fix.lat, fix.lng)
                    updateLocationPuck(fix)
                    MapTilePrefetcher.schedulePrefetch(
                        applicationContext, STYLE_URL, fix.lat, fix.lng, resources.displayMetrics.density
                    )
                } else {
                    recenterPending = true
                    showSnackbar(getString(R.string.roadsense_map_no_location))
                }
            }
        }
    }

    /**
     * Draw/move the location puck: MapLibre's native location-indicator layer with the
     * directional arrow over a soft ground shadow. Its pose is a paint property, so it moves
     * in the same frame as the camera (a GeoJSON source is re-tiled on a worker and lands a
     * frame late). Falls back to the last bearing when the fix has none. Created lazily on
     * the first fix, above the hazard layers.
     */
    private fun updateLocationPuck(fix: RoadSenseHazardApiClient.LatLngFix) {
        val bearing = fix.bearing?.takeIf { fix.bestSpeedMps > IMMERSIVE_MIN_SPEED_MPS } ?: lastBearing
        updateLocationPuckAt(fix.lat, fix.lng, bearing)
    }

    private var puckLayer: org.maplibre.android.style.layers.Layer? = null
    private var puckLayerStyle: org.maplibre.android.maps.Style? = null
    private val puckLocation = arrayOf(0.0, 0.0, 0.0)

    /** Place the location puck at an explicit (lat,lng) + heading (the frame loop's pose, or a fix). */
    private fun updateLocationPuckAt(lat: Double, lng: Double, bearing: Double) {
        val style = map?.style ?: return
        // A theme reload swaps the style; skip until it has loaded (onStyleLoaded repaints the puck).
        if (!style.isFullyLoaded) return
        val layer = puckLayer?.takeIf { puckLayerStyle === style } ?: run {
            puckPaintedLat = Double.NaN
            createPuckLayer(style, lat, lng, bearing) ?: return
        }
        // A held pose is not rewritten, so a parked map renders nothing.
        if (!puckPaintedLat.isNaN()) {
            val moved = guidance.haversineMeters(puckPaintedLat, puckPaintedLng, lat, lng)
            var dBearing = kotlin.math.abs(bearing - puckPaintedBearing) % 360.0
            if (dBearing > 180.0) dBearing = 360.0 - dBearing
            if (moved < PUCK_DEADBAND_M && dBearing < PUCK_DEADBAND_DEG) return
        }
        puckLocation[0] = lat
        puckLocation[1] = lng
        layer.setProperties(
            org.maplibre.android.style.layers.PaintPropertyValue("location", puckLocation),
            org.maplibre.android.style.layers.PaintPropertyValue("bearing", bearing)
        )
        puckPaintedLat = lat; puckPaintedLng = lng; puckPaintedBearing = bearing
    }

    private fun createPuckLayer(
        style: org.maplibre.android.maps.Style, lat: Double, lng: Double, bearing: Double
    ): org.maplibre.android.style.layers.Layer? = try {
        // Glass chevron + ground shadow for this theme; a theme switch swaps the style, so this re-registers them.
        com.overdrive.app.navmap.glass.MapGlassArt.registerStatic(this, style, isNightTheme())
        puckLocation[0] = lat
        puckLocation[1] = lng
        val l = org.maplibre.android.location.OverDrivePuckLayers.create(PUCK_CORE_LAYER_ID)
        l.setProperties(*com.overdrive.app.navmap.glass.MapGlassArt.puckLayerProps())
        l.setProperties(
            org.maplibre.android.style.layers.PaintPropertyValue("accuracy-radius", 0f),
            org.maplibre.android.style.layers.PaintPropertyValue("location", puckLocation),
            org.maplibre.android.style.layers.PaintPropertyValue("bearing", bearing)
        )
        style.addLayer(l)
        puckLayer = l
        puckLayerStyle = style
        puckPaintedLat = lat; puckPaintedLng = lng; puckPaintedBearing = bearing
        l
    } catch (t: Throwable) {
        Log.w("RoadSenseMap", "puck layer unavailable: ${t.javaClass.simpleName}")
        null
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (clusterMode) {
            layoutGeneration = intent.getLongExtra("layoutGeneration", 0L)
            layoutDisplayId = if (layoutGeneration > 0) intent.getIntExtra("layoutDisplayId", -1) else -1
            clusterFinishing = false
            mainHandler.removeCallbacks(clusterFinishPoll)
            startClusterFinishPoll()
            if (::mapView.isInitialized) applyClusterChrome()
        }
        // A trip saved for a map that's already open (singleTop): start it as a fresh launch would.
        if (clusterMode || !intent.hasExtra(NavTripStore.EXTRA_RESTORE_TRIP)) return
        // the new trip replaces one waiting for the map to come back
        routeOnReturn = false
        fixWaitOnReturn = false
        didRestoreTrip = false
        // and guidance still running from before ends now, keeping the saved trip: nothing of the old one (its settle on
        // resume, its next tick, a wait for the style) may save over the new one
        endOldGuidanceFor(NavTripStore.hasStart(applicationContext))
        if (map?.style?.isFullyLoaded == true) restoreTripIfAny()
    }

    // A new trip is coming in: where the old guidance left the car is no longer anyone's business, and (for one asking
    // to start) the old guidance ends.
    private fun endOldGuidanceFor(start: Boolean) {
        clearLeftAt()
        if (!start) return
        if (navigating) endTrip(keepSaved = true) else clearRoutePreview(keepSaved = true)
        // nor does an old one still routing get to start (a restore retrying, an earlier yes waiting for a fix)
        cancelRouteRetry()
        routeOnFreshFix = false
    }

    override fun onResume() {
        super.onResume()
        mapStopped = false
        if (!::mapView.isInitialized) return
        mapView.onResume()
        // a stop passed meanwhile, on this trip still: the way back to it
        pendingGoBack?.let { (stop, dest, at) ->
            pendingGoBack = null
            if (android.os.SystemClock.elapsedRealtime() - at <= GO_BACK_PENDING_MS && navigating && !clusterMode &&
                routeStops.lastOrNull() === dest && routeStops.none { it === stop }
            ) showGoBack(stop, dest)
        }
        // After onNewIntent: back to a briefing trip left while routing, it carries on if still fresh, else goes.
        if (routeOnReturn) {
            routeOnReturn = false
            // one that was waiting for GPS (a garage, say) keeps waiting as long as it would have; either way only
            // after a short absence (the reverse camera, a glance at another app), not a drive elsewhere
            val cap = if (fixWaitOnReturn) FIX_WAIT_MAX_MS else START_REQUEST_MAX_AGE_MS
            fixWaitOnReturn = false
            val now = android.os.SystemClock.elapsedRealtime()
            // (away since the yes was set aside, or since the map went, whichever is later)
            val since = maxOf(backgroundedAtMono, routeOnReturnSince)
            val away = if (since > 0L) now - since else 0L
            if (!navigating && routeStops.isNotEmpty()) {
                if (briefingStartAt > 0L && now - briefingStartAt <= cap && away <= START_REQUEST_MAX_AGE_MS) {
                    routeRetries = 0
                    recomputeItinerary(autoStart = true, retry = true)
                } else {
                    briefingStartAt = 0L
                    clearRoutePreview()
                }
            }
        }
        if (::googleMap.isInitialized) {
            googleMap.onResume()
            // The key/settings may have changed in the web UI: reload them off the looper, then the FAB.
            // load() republishes the cached copy, so main-thread readers never hit an empty cache.
            ioExecutor().execute {
                try { GoogleMapsConfig.load() } catch (_: Throwable) {}
                mainHandler.post { if (!isFinishing && !isDestroyed) updateMapLayersFab() }
            }
        }
        // RESUME-SNAP: while backgrounded the frame loop was suspended, so the estimator's
        // filtered state + the shown pose are frozen at the position from when we left;
        // resuming would glide from that stale spot to the live fix. After a real
        // away-period, hard-RESET the estimator (next truth fix SEEDS directly) and the
        // shown pose (it re-seeds at the first fix). The fresh direct-GPS subscription / daemon poll started below delivers
        // that current fix within ~1s. A momentary flip (< the threshold) skips the reset so
        // a quick notification-shade peek doesn't re-seed needlessly.
        val awayMs = if (backgroundedAtMono > 0L)
            android.os.SystemClock.elapsedRealtime() - backgroundedAtMono else 0L
        backgroundedAtMono = 0L
        if (awayMs >= RESUME_SNAP_AFTER_MS) {
            motionEstimator.reset()        // next onTruthPoint seeds directly (no glide)
            motionModel.reset()            // the shown pose re-seeds at the first fix (no catch-up)
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
        val sleptAway = if (NavSession.headUnitTickAt > 0L) sleptSinceTick() else awayMs
        if (!settleLeftGuidance(sleptAway) && sleptAway > STALE_TRIP_MS && navigating && !clusterMode) endTrip()
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
            // refreshes on resume and keeps tracking. Back after a long while, the
            // camera follows the car again even if the driver had panned.
            if (awayMs >= IDLE_FOLLOW_REARM_MS) idleFollowCamera = true
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
        if (::googleMap.isInitialized) {
            googleMap.onPause()
            GoogleMapsBudget.flush()
            GoogleUsageReporter.maybeReport(true)
        }
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        // a return (or a restart) a long while later knows how long guidance was away, and where the car was left
        if (navigating && !clusterMode) {
            noteLeftAt(lastFix)
            touchTrip(leftAtDest, leftAtStop != null, leftStanding)
        }
        // Suspend the cluster-follow loops while backgrounded — the SurfaceView render is paused here, so firing network
        // GETs + animateCamera every second just drains the SoC. Head-unit guidance itself carries on behind another app
        // (voice, reroutes, stops, arrival, the cluster's mirror); only its drawing stops. The `navigating`/`clusterMode`
        // flags are KEPT so onResume restarts seamlessly.
        mapStopped = true
        if (clusterMode || !navigating) mainHandler.removeCallbacks(guidanceRunnable)
        mainHandler.removeCallbacks(recenterAfterFree)
        mainHandler.removeCallbacks(clusterFollowRunnable)
        mainHandler.removeCallbacks(clusterKeepWarmRunnable)   // suspend the keep-warm repaint nudge
        mainHandler.removeCallbacks(glSurfaceRebuild)
        mainHandler.removeCallbacks(idleFollowRunnable)
        saveLastCamera()
        // back after a while, the car may be far from where the cluster camera stopped
        clusterPlaced = false
        // A briefing trip still routing (or waiting for a fix) doesn't start guidance unseen, nor outlive a map that
        // isn't coming back.
        if ((autoRouting || routeOnFreshFix) && briefingStartAt > 0L) {
            fixWaitOnReturn = routeOnFreshFix || lastRouteFailNoFix || briefingWaitedForFix
            cancelRouteRetry()
            routeOnFreshFix = false
            routeOnReturn = true
            NavTripStore.clear(applicationContext)
        }
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
        NavSession.removeTrafficListener(clusterTrafficListener)
        clusterTrafficListener = null
        stopTrafficRefresh()
        glassLayers.detach()
        try { navVoice?.shutdown() } catch (_: Throwable) {}
        navVoice = null
        ioExecutor?.shutdownNow()
        ioExecutor = null
        MapTilePrefetcher.cancelPending()
        mainHandler.removeCallbacks(prefetchPlacesTask)
        hazardSource = null
        // Null symmetry: drop the remaining style-bound source/layer + sheet refs
        // so they don't pin a destroyed Style/MapView (no-op safety; map is gone).
        poiSource = null
        routeSheetBehavior = null
        if (::googleMap.isInitialized) googleMap.onDestroy()
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
         * OpenFreeMap hosted liberty — no API key, no limits; both themes use it (MapGlassBasemap
         * recolours it at night). STYLE_URL is also the prefetch reference.
         */
        const val STYLE_URL_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
        const val STYLE_URL = STYLE_URL_LIGHT

        /**
         * BUNDLED on-device style assets (preferred over the hosted URLs above). These
         * are the SAME OpenFreeMap basemap recipe shipped in the APK so the map opens
         * instantly without a style-document fetch, survives a flaky link, and is ours
         * to tune. The tiles/sprite/glyphs referenced inside are still streamed from
         * OpenFreeMap. Loaded via Style.Builder().fromJson(asset) in vectorStyleBuilder();
         * the hosted URL remains the fallback if an asset can't be read.
         */
        const val STYLE_ASSET_LIGHT = "maps/liberty_style.json"

        // Clustered hazard source (drawn by MapGlassLayers).
        const val HAZARD_SOURCE_ID = "roadsense-hazards"

        // GeoJSON property keys (match RoadSenseApiHandler output).
        const val PROP_ID = "id"
        const val PROP_TYPE = "type"
        const val PROP_SEVERITY = "severity"
        const val PROP_CONFIDENCE = "confidence"
        const val PROP_STATUS = "status"
        const val PROP_OBSERVATIONS = "observations"

        // Clustering.
        const val CLUSTER_RADIUS = 50
        // Up to z16 nearby hazards merge into a count; the follow camera (≈z17) shows each one.
        const val CLUSTER_MAX_ZOOM = 16

        // Pin bitmap width (px); height is 1.3x. Large for hi-dpi crispness.
        const val PIN_PX = 96

        const val REFETCH_DEBOUNCE_MS = 400L
        const val RECENTER_ANIM_MS = 900

        // Initial camera until a recenter / live fix narrows it.
        const val DEFAULT_LAT = 1.3521
        const val DEFAULT_LNG = 103.8198
        const val DEFAULT_ZOOM = 13.0
        const val KEY_LAST_CAM_LAT = "last_cam_lat"
        const val KEY_LAST_CAM_LNG = "last_cam_lng"
        const val KEY_LAST_CAM_ZOOM = "last_cam_zoom"
        const val CLUSTER_PLACE_FAR_M = 5_000.0
        const val CLUSTER_FIX_FRESH_MS = 10_000L
        const val SNAP_FAR_M = 1_000.0
        const val SNAP_MAX_ACCURACY_M = 50.0
        const val SNAP_CONFIRM_M = 300.0
        /** How long after a briefing yes a missing fix may still start its guidance. */
        const val FIX_WAIT_MAX_MS = 10 * 60_000L
        /** A far fix is confirmed only by another fix measured this much later, and no later than the window. */
        const val SNAP_CONFIRM_GAP_MS = 500L
        /** Stood this long within this of the destination (no stop left; [PARKED_NEAR_MS] with one): arrived, whatever the route says. */
        const val LONG_STOP_RADIUS_M = 60.0
        const val LONG_STOP_MS = 60_000L
        /** Stood off the road this near the pin (its car park) for [LONG_STOP_MS]: arrived. */
        const val NEAR_OFF_ROAD_RADIUS_M = 60.0
        /** The route-end near-stop needs the car this near the pin too (not stopped down the road past the last turn). */
        const val NEAR_STOP_PIN_M = 50.0
        const val AT_PIN_STOP_M = 30.0
        /** More route than this still to go: standing by the pin needs the longer waits (a queue on the near side). */
        const val PIN_ROUTE_LEFT_M = 400.0
        /** Standing this near the pin, on the road or off, for [NEAR_LONG_MS]: arrived. */
        const val NEAR_LONG_RADIUS_M = 150.0
        /** Off the road: this far from the route (a light or a queue on it is nearer). */
        const val NEAR_STOP_OFF_ROAD_M = 30.0
        /** Stood on the road at the pin this long: arrived (a light is shorter). */
        const val ON_ROAD_AT_PIN_MS = 150_000L
        /** In P this long off the road within [NEAR_LONG_RADIUS_M] of the destination (its car park): arrived. */
        const val PARKED_BY_MS = 3_000L
        /** The gear known, more route than this left makes standing by the pin a queue: the longer wait. */
        const val PIN_ROUTE_LEFT_IN_GEAR_M = 150.0
        /** A gear reading is kept this long over readings gone stale. */
        const val GEAR_HOLD_MS = 5_000L
        /** A route from a crawl leaves along the car's track over this far, if it ran this straight, from this recently. */
        const val TRACK_HEADING_M = 25.0
        const val TRACK_STRAIGHT = 0.8
        const val TRACK_MAX_AGE_MS = 90_000L
        private const val TRACK_MIN_MPS = 0.5
        private const val TRACK_N = 60
        /** Parked by the destination, but this much route still to go: a long way round (a motorway, a river), not there. */
        const val LEFT_ROUTE_MAX_M = 1_500.0
        /** Frequent places are prefetched this long after the map is up (not competing with its first tiles). */
        const val PLACES_PREFETCH_DELAY_MS = 30_000L
        /** A stop passed behind another app is offered back on return this long after. */
        const val GO_BACK_PENDING_MS = 2 * 60_000L
        /** A changed destination this far from the old one is another trip's. */
        const val NEW_DEST_M = 150.0
        private const val GEAR_REVERSE = "R"
        /** Switched off, then this far on: running again (the daemon's ACC on can lag). */
        const val SWITCHED_OFF_MOVED_M = 40.0
        private const val GEAR_PARK = "P"
        /** Guidance stopped with the car this near its destination / its next stop, back after a long while: done. */
        const val LEFT_AT_DEST_M = 400.0
        const val LEFT_AT_STOP_M = 150.0
        /** …and standing this near it then: back after the short wait ([LEFT_PARKED_RETURN_MS]) is enough. */
        const val LEFT_NEAR_DEST_M = 150.0
        const val LEFT_NEAR_STOP_M = 60.0
        /** A guiding trip whose guidance hasn't run for this long isn't picked up again. */
        const val STALE_TRIP_MS = 2 * 60 * 60_000L
        /** A guiding trip picked up after this long comes back as a preview, not guidance. */
        const val RESUME_AS_PREVIEW_MS = 30 * 60_000L
        /** No reroute this near the destination (no stop left) at car-park speed: leaving the road there is its car park. */
        const val NO_REROUTE_NEAR_DEST_M = 150.0
        const val CAR_PARK_MPS = 4.0
        /** A car left standing by its destination or next stop counts as there after this long away. */
        const val LEFT_PARKED_RETURN_MS = 2 * 60_000L
        /** Standing this long within [NEAR_LONG_RADIUS_M] of the destination, on the road or off: arrived. */
        const val NEAR_LONG_MS = 5 * 60_000L
        /** Standing this long within [LEFT_AT_DEST_M] of the destination, the gear unknown: arrived (a big car park). */
        const val PARKED_FAR_MS = 10 * 60_000L
        const val PARKED_NEAR_MS = 180_000L
        /** The saved trip is stamped this often while guiding (for a restart's "how long away, from where"). */
        const val TRIP_TOUCH_MS = 30_000L
        const val TRIP_TOUCH_MIN_MS = 5_000L
        /** A reroute this near the next stop offers to skip it, for this long. */
        const val SKIP_OFFER_M = 1_500.0
        const val SKIP_OFFER_SHOW_MS = 20_000
        const val CLUSTER_OFFER_SHOW_MS = 15_000
        const val CLUSTER_OFFER_CLIP = "brief_cluster_offer"
        /** Back on the map after this long, the camera follows the car again even if it had been panned. */
        const val IDLE_FOLLOW_REARM_MS = 5 * 60_000L
        /** A camera move whose end never reported holds the idle follow no longer than this. */
        const val CAMERA_BUSY_MAX_MS = 3_000L
        /** Guidance back after this long with the car this near its destination ended there meanwhile. */
        const val STALE_GUIDANCE_GAP_MS = 10 * 60_000L
        const val STALE_ARRIVED_M = 200.0
        const val SNAP_CONFIRM_WINDOW_MS = 30_000L
        const val STYLE_RETRY_MS = 5_000L
        const val STYLE_RETRY_MAX = 3

        // Zoom used when centering on the live GPS fix (closer than the
        // overview default so the user sees their immediate surroundings).
        const val FOLLOW_ZOOM = 16.0

        // Location puck source + layers (kept above the hazard layers).
        const val PUCK_CORE_LAYER_ID = "roadsense-location-core"

        // Dropped-pin marker (long-press the map to save/navigate a place).
        const val DROPPED_PIN_SOURCE_ID = "roadsense-dropped-pin"
        const val DROPPED_PIN_LAYER_ID = "roadsense-dropped-pin-layer"
        const val ICON_DROPPED_PIN = "rs_dropped_pin"

        // How many ALTERNATE routes to request (Valhalla may return fewer).
        const val ROUTE_ALTERNATES = 2

        // A restored trip retries routing every 5 s for a minute (no fix or no data yet at car start).
        const val ROUTE_RETRY_MS = 5_000L
        const val ROUTE_RETRY_MAX = 120
        const val START_REQUEST_MAX_AGE_MS = 120_000L
        const val RESTORE_FIX_MAX_AGE_MS = 60_000L

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
        /** Google Places autocomplete is fast and session-billed, so it debounces shorter. */
        const val GOOGLE_AUTOCOMPLETE_DEBOUNCE_MS = 150L
        /** A live-traffic ETA older than this falls back to the guidance engine's ETA. */
        const val TRAFFIC_ETA_MAX_AGE_MS = 900_000L
        /** The faster-route prompt auto-dismisses after this. */
        const val FASTER_ROUTE_PROMPT_MS = 12_000L
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
        /** Frame-loop freeze-guard threshold (ms): when the estimator baseline is this old
         *  and no truth fix has refreshed it, reanchorToLastFix() holds the puck at the last
         *  real position. MUST be < MAX_DEAD_RECKON_S (3000 ms) by a comfortable margin so a
         *  re-anchor always lands before the dead-reckon caps. The frame loop checks it every
         *  frame, so there is no 1 Hz quantization. 2200 ms leaves ~800 ms headroom below the cap. */
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

        /** Cluster keep-warm repaint interval (ms): bounds how long a silently-recreated
         *  WHEN_DIRTY surface can stay black before a triggerRepaint() repaints it. ~3s is
         *  a negligible GPU cost (one frame/3s) vs the parked-car black-out risk. */
        const val CLUSTER_KEEP_WARM_MS = 3000L
        /** Head unit: delay after a new map surface before its EGL surface is rebuilt (the buffer size has landed by then). */
        const val GL_SURFACE_REBUILD_MS = 300L

        // GL render-rate cap (MapView.setMaximumFps) on top of WHEN_DIRTY while the frame
        // loop is idle (gestures, tile loads). While it runs, capRenderRate lifts the cap
        // just above the loop's rate. The cluster shares the GPU with the camera pipeline.
        const val HEADUNIT_MAX_FPS = 30
        const val CLUSTER_MAX_FPS = 15
        // Head unit while the cluster mirrors the drive: one frame in 12 (5 fps).
        const val PROJECTION_FRAME_DIVISOR = 12
        // HUD live values (distance, speed, re-centre countdown) refresh at 4 Hz.
        const val HUD_TICK_MS = 250L
        // "Then" tab: the next maneuver within ~30 s of travel after the current one (400 m – 1.2 km).
        const val THEN_WINDOW_S = 30.0
        const val THEN_MIN_M = 400.0
        const val THEN_MAX_M = 1200.0
        // On-road turn arrow: turns, ramps, exits, keeps, roundabouts and side merges, shown this close.
        val ARROW_TYPES = setOf(9, 10, 11, 12, 13, 14, 15, 16, 18, 19, 20, 21, 23, 24, 26, 27, 37, 38)
        const val ARROW_NEAR_M = 600.0
        const val ARROW_FAST_M = 900.0
        const val ARROW_FAST_MPS = 70.0 / 3.6
        const val NORTH_UP_ANIM_MS = 700L

        // Head-unit poll cadence for the daemon's clusterMapActive flag (dual-render
        // avoidance). 1s matches the GPS/guidance cadence — the head unit doesn't need
        // to learn about a projection start/stop faster than that, and the read is a
        // cross-UID UCM forceReload kept off the looper. Mirrors CLUSTER_FINISH_POLL_MS's
        // role on the cluster side.
        const val PROJECTION_POLL_MS = 1000L

        const val REROUTE_MIN_INTERVAL_MS = 8000L // min gap between auto-reroutes

        // Near-stop arrival: when the along-route remainder is inside this band AND
        // the car has slowed to (near) a stop, declare arrival even if GPS parks us
        // just outside the strict ARRIVAL_RADIUS_M. This is the "be smart enough to
        // end nav near the destination instead of rerouting" behaviour — pairs with
        // NavGuidanceEngine.NEAR_DEST_NO_REROUTE_M which suppresses reroutes in the
        // same band so a slow final-approach wander can't trigger a recompute.
        const val NEAR_STOP_ARRIVAL_M = 60.0 // inside the "final 50-100 m" the user flagged
        const val NEAR_STOP_SPEED_MPS = 1.5  // ~5.4 km/h — genuinely stopped, not a slow crawl
        const val NEAR_STOP_ARRIVAL_TICKS = 5 // consecutive near-stop ticks (≈5 s at 1 Hz), off the road, to confirm

        // Puck rewrite dead-band: a pose change this small is not written, so a parked
        // map emits no frames. Far below one frame of travel while driving.
        const val PUCK_DEADBAND_M = 0.02
        const val PUCK_DEADBAND_DEG = 0.05

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
        const val MAP_2D_BUILDING_LAYER_ID = "building"
        const val MAP_3D_SOURCE = "openmaptiles"
        const val MAP_3D_SOURCE_LAYER = "building"
        const val MAP_3D_MIN_ZOOM = 14f
        // Fixed extrusion hues (NOT theme attrs — these tint 3D building volumes on
        // the basemap, so they track the BASEMAP theme, not the app M3 palette).
        const val MAP_3D_COLOR_LIGHT = "hsl(35,8%,85%)" // liberty's own building tint
        const val MAP_3D_COLOR_DARK = "#2b2d36"          // muted slate for night
        const val KEY_MAP_3D = "map_3d_enabled" // persisted 2D/3D choice (default 3D)

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
