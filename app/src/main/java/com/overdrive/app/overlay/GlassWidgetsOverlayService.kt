package com.overdrive.app.overlay

import com.overdrive.app.overlay.glass.gs
import com.overdrive.app.overlay.glass.GlassStrings
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.SizeF
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.overlay.glass.CustomDoc
import com.overdrive.app.overlay.glass.CustomSignals
import com.overdrive.app.overlay.glass.GlassArrangeLayer
import com.overdrive.app.overlay.glass.GlassOrbWindow
import com.overdrive.app.overlay.glass.GlassBatteryView
import com.overdrive.app.overlay.glass.GlassChargingView
import com.overdrive.app.overlay.glass.GlassControlsView
import com.overdrive.app.overlay.glass.GlassCustomView
import com.overdrive.app.overlay.glass.GlassDriveView
import com.overdrive.app.overlay.glass.GlassDynamicsView
import com.overdrive.app.overlay.glass.GlassFreeLayout
import com.overdrive.app.overlay.glass.GlassPickerSheet
import com.overdrive.app.overlay.glass.GlassPowerView
import com.overdrive.app.overlay.glass.GlassRoadSenseView
import com.overdrive.app.overlay.glass.GlassShortcutsView
import com.overdrive.app.overlay.glass.GlassSurface
import com.overdrive.app.overlay.glass.GlassTheme
import com.overdrive.app.overlay.glass.GlassTicker
import com.overdrive.app.overlay.glass.GlassTripView
import com.overdrive.app.overlay.glass.GlassEnv
import com.overdrive.app.overlay.glass.GlassWidgetView
import com.overdrive.app.overlay.glass.GlassWidgets
import com.overdrive.app.overlay.glass.GlassWidgetsHost
import com.overdrive.app.services.DaemonKeepaliveService
import com.overdrive.app.services.KeyMapDispatcher
import com.overdrive.app.util.ScratchRoot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Glass widgets: the user's chosen widgets, drawn natively, over the home screen or over every app.
 * They sit in one stacked window, or each in its own window once the user has arranged them (a
 * long press opens arrange mode). Core is read over loopback HTTP with NO_PROXY, so the loopback
 * proxy never sees it; settings and RoadSense state come from UCM, and writes go through core.
 */
class GlassWidgetsOverlayService : Service() {

    private data class Shortcut(val id: String, val name: String, val glyph: String)

    private class Pending<T>(val value: T, val at: Long = SystemClock.elapsedRealtime())

    /** What Done saves: the spots, the screen's shape, and the widget lists when arranging changed them. */
    private data class Arrangement(val layout: Map<String, GlassFreeLayout.Spot>, val portrait: Boolean?, val widgets: List<String>?, val custom: List<String>? = null)

    /** A custom or ready-made widget's doc and the text it was read from (an unchanged text keeps the doc). */
    private class Loaded(val text: String, val doc: CustomDoc)

    /** Docs read for the [asked] ids; an asked id missing from [found] has none (deleted, or invalid). */
    private class Docs(val asked: Set<String>, val found: Map<String, Loaded>)

    private data class Settings(
        val enabled: Boolean,
        val mode: String,
        val widgets: List<String>,
        val position: String,
        val offsetX: Int,
        val offsetY: Int,
        val scale: Float,
        val theme: String,
        val tint: Float,
        val reduceMotion: Boolean,
        val motors: String,
        val chargingAuto: Boolean,
        val units: String,
        /** Distances and speeds in miles: the unit picked for trips. */
        val miles: Boolean,
        val showBs: Boolean,
        /** The floating shortcuts button over every app, with the same three automations. */
        val floating: Boolean,
        /** RoadSense shows altitude and slope on a clear road while driving. */
        val terrain: Boolean,
        val layout: Map<String, GlassFreeLayout.Spot>,
        /** The screen was portrait when [layout] was arranged (null: saved before this was kept, so either way round). */
        val layoutPortrait: Boolean?,
        val shortcuts: List<Shortcut?>,
        /** User-designed ("custom:") and ready-made ("bundled:") widgets on screen, in order, after the built-ins. */
        val custom: List<String> = emptyList(),
    ) {
        val vertical get() = position == "left" || position == "right"

        /** Core bumps it after every change to its store of custom widgets: the io thread's alone, outside equality. */
        var customVersion = 0
            private set

        companion object {
            val ALL = GlassWidgets.IDS
            private val POSITIONS = setOf("bottom-left", "bottom-center", "bottom-right", "top-left", "top-right", "left", "right")
            /** A custom or ready-made widget's id: the prefix, then a plain name. */
            val CUSTOM_ID = Regex("(custom|bundled):[A-Za-z0-9_-]{1,64}")
            private const val MAX_CUSTOM = 40

            /** [units] is the app-wide tyre pressure display unit, not a widget setting. */
            fun from(o: JSONObject?, units: String, miles: Boolean = false): Settings {
                val list = o?.optJSONArray("widgets")
                val widgets = if (list == null) listOf("battery", "charging", "trip", "power")
                else (0 until list.length()).map { list.optString(it) }.filter { it in ALL }.distinct()
                val custom = custom(o?.optJSONArray("custom"))
                return Settings(
                    enabled = o?.optBoolean("enabled", false) ?: false,
                    mode = if (o?.optString("mode") == "global") "global" else "home",
                    widgets = widgets,
                    position = o?.optString("position")?.takeIf { it in POSITIONS } ?: "bottom-left",
                    offsetX = (o?.optInt("offsetX", 24) ?: 24).coerceIn(0, 800),
                    offsetY = (o?.optInt("offsetY", 24) ?: 24).coerceIn(0, 800),
                    scale = (o?.optDouble("scale", 1.0) ?: 1.0).let { if (it.isNaN()) 1.0 else it }.coerceIn(0.6, 1.3).toFloat(),
                    theme = o?.optString("theme")?.takeIf { it == "night" || it == "day" } ?: "auto",
                    tint = (o?.optDouble("tint", 0.82) ?: 0.82).let { if (it.isNaN()) 0.82 else it }.coerceIn(0.7, 0.95).toFloat(),
                    reduceMotion = o?.optBoolean("reduceMotion", false) ?: false,
                    motors = o?.optString("motors")?.takeIf { it in setOf("awd", "fwd", "rwd") } ?: "auto",
                    chargingAuto = o?.optBoolean("chargingOnlyWhenActive", true) ?: true,
                    units = units,
                    miles = miles,
                    showBs = o?.optBoolean("showBlindSpot", true) ?: true,
                    floating = o?.optBoolean("floatingShortcuts", false) ?: false,
                    terrain = o?.optBoolean("terrain", true) ?: true,
                    layout = layout(o?.optJSONObject("layout"), custom),
                    layoutPortrait = if (o != null && o.has("layoutPortrait") && !o.isNull("layoutPortrait")) o.optBoolean("layoutPortrait") else null,
                    shortcuts = shortcuts(o?.optJSONArray("shortcuts")),
                    custom = custom,
                ).also { it.customVersion = o?.optInt("customVersion", 0) ?: 0 }
            }

            private fun custom(a: JSONArray?): List<String> {
                if (a == null) return emptyList()
                return (0 until a.length()).map { a.optString(it) }.filter { CUSTOM_ID.matches(it) }.distinct().take(MAX_CUSTOM)
            }

            // the built-ins' spots, and those of the custom widgets listed
            private fun layout(o: JSONObject?, custom: List<String>): Map<String, GlassFreeLayout.Spot> {
                if (o == null) return emptyMap()
                val out = LinkedHashMap<String, GlassFreeLayout.Spot>()
                for (id in ALL + custom) {
                    val p = o.optJSONObject(id) ?: continue
                    val x = p.optDouble("x", Double.NaN); val y = p.optDouble("y", Double.NaN)
                    if (x.isNaN() || y.isNaN()) continue
                    val size = p.optDouble("s", 1.0).let { if (it.isNaN()) 1.0 else it }.toFloat().coerceIn(GlassArrangeLayer.MIN_SIZE, GlassArrangeLayer.MAX_SIZE)
                    out[id] = GlassFreeLayout.Spot(x.toFloat().coerceIn(0f, 1f), y.toFloat().coerceIn(0f, 1f), p.optBoolean("r"), p.optBoolean("b"), size)
                }
                return out
            }

            private fun shortcuts(a: JSONArray?): List<Shortcut?> = List(GlassShortcutsView.SLOTS) { i ->
                val o = a?.optJSONObject(i)
                val id = o?.optString("id")?.takeIf { it.isNotEmpty() }
                if (o == null || id == null) null else Shortcut(id, o.optString("name"), o.optString("glyph", "bolt"))
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var ioThread: HandlerThread? = null
    private var io: Handler? = null
    private var feedThread: HandlerThread? = null
    private var feed: Handler? = null
    // the live drive samples get their own thread: the summary poll's latency would space them unevenly
    private var liveThread: HandlerThread? = null
    private var liveH: Handler? = null
    private val net: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "glass-widgets-net").apply { isDaemon = true } }
    // config writes go one at a time, in order: core keeps whichever lands last
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "glass-widgets-save").apply { isDaemon = true } }

    // main thread
    private val env = GlassEnv()
    private val ticker = GlassTicker()
    private var surface: GlassSurface? = null
    private var host: GlassWidgetsHost? = null
    private var lp: WindowManager.LayoutParams? = null
    // stack mode's custom widgets: a row (or column) of their own in a second window beside the built-ins',
    // at its own scale with its own copy of [env], so showing one never moves or shrinks a built-in
    private var customHost: GlassWidgetsHost? = null
    private var customLp: WindowManager.LayoutParams? = null
    private var customEnv: GlassEnv? = null
    private var customScale = 1f
    private var customBeside: List<String> = emptyList()
    private var free: GlassFreeLayout? = null
    private var arrange: GlassArrangeLayer? = null
    private var pendingArrange = false
    private var pendingArrangeAt = 0L
    // Home (and Recents) leave arrange mode as Back does: it must never hold the screen
    private val homeKeys = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val reason = i?.getStringExtra("reason")
            if (reason == "homekey" || reason == "recentapps") arrange?.cancel()
        }
    }
    private var homeKeysOn = false
    private var screenW = 0
    private var screenH = 0
    private var settings: Settings? = null
    private var night: Boolean? = null
    private var snapshot: GlassWidgetsFeed.Snapshot? = null
    private var controls = GlassControlsView.State()
    private var road: GlassWidgetsFeed.Road? = null
    private var homeHidden = false
    private var offline = false
    private var lockHoldUntil = 0L
    private var climateHoldUntil = 0L
    private var windowsHoldUntil = 0L
    private var seenBattery = false
    private var seenTrip = false
    private var calibratedKm: Int? = null
    private var rangeJson: JSONObject? = null
    // reads of core's range that failed in a row
    private var rangeFails = 0
    // writes of ours on their way to core: until every one has answered and the config read back
    // carries the last, ours wins
    private var pendingLayout: Pending<Arrangement>? = null
    // an arrangement that added or took off the power widget, until the daemon is told (once it's stored)
    private var powerSyncFor: Pending<Arrangement>? = null
    // the widget list arrange mode began with: Done applies only its own additions and removals
    private var arrangeFrom: List<String> = emptyList()
    private var pendingShortcuts: Pending<List<Shortcut?>>? = null
    private var layoutWrites = 0
    private var shortcutWrites = 0
    private var layoutAckAt = 0L
    private var shortcutAckAt = 0L
    private var autos: List<GlassPickerSheet.Item>? = null
    private var autosInFlight = 0
    private var autosFailed = false
    // ids core answered 404 for, shown as removed until a list asked after that says otherwise
    private val removedIds = HashSet<String>()
    // one command per control at a time, also across a rebuilt widget (a rotation, arrange mode)
    private val commandsInFlight = HashSet<String>()
    // lists apply in the order they were asked for: a slow early one never replaces a newer one
    private val autosAsked = java.util.concurrent.atomic.AtomicInteger()
    private var autosApplied = 0
    private var destroyed = false
    private var picker: FrameLayout? = null
    private var pickerSlot = -1
    // the chooser was opened from the floating button's panel, not the shortcuts widget
    private var pickerFromOrb = false
    private var orb: GlassOrbWindow? = null
    private var pickerFailed = false
    private var regenKwh = 0.0
    private var driveKwh = 0.0
    private var lastLiveAt = 0L
    // the live poll's blind-spot reading, fresher than the summary's while it runs; the drive trace's last sample
    private var liveBs: GlassWidgetsFeed.Live? = null
    private var liveBsAt = 0L
    private var lastDriveSampleAt = 0L
    private var tripKmSeen = -1f
    private var tripLiveSeen = false
    // custom widgets: the text each registered doc was read from, docs read while arranging (applied
    // after), the list arranging began with, and the live poll's last reading
    private val docTexts = HashMap<String, String>()
    private var deferredDocs: Docs? = null
    private var customFrom: List<String> = emptyList()
    private var customLive: GlassWidgetsFeed.Live? = null
    private var customLiveAt = 0L

    // io / feed threads
    @Volatile private var running = false
    @Volatile private var readSettings: Settings? = null
    @Volatile private var lastOk = 0L
    @Volatile private var wantVehicleState = false
    @Volatile private var wantCalibrated = false
    @Volatile private var wantAutos = false
    @Volatile private var wantLive = false
    // drive dynamics shows: the live poll runs four times a second and reads the pedals too
    @Volatile private var liveDynamics = false
    @Volatile private var liveMs = LIVE_MS
    @Volatile private var feedDriving = false
    @Volatile private var feedHidden = false
    // a shown custom widget reads the pedals (the live poll asks for them), or RoadSense (read like the widget's)
    @Volatile private var livePedals = false
    @Volatile private var customRoad = false
    // the custom widgets' docs as last read (io thread)
    private var docsVersion = 0
    private var docsList: List<String>? = null
    private var docsAsked: Set<String> = emptySet()
    private var docsFails = 0
    private var lastVehicleState = 0L
    private var lastCalibrated = 0L
    private var lastAutos = 0L
    private var liveScheduled = false
    private var lastSettings: String? = null
    private val settingsLock = Any()
    private var lastRoadKey: String? = null
    private var hiddenSent: Boolean? = null
    private var disabledReads = 0
    // the daemon's last answer about the main screen's top task (io thread)
    private var probePkg: String? = null
    private var probeCls: String? = null
    private var probeBase: String? = null
    private var probeHomeTask: Boolean? = null
    private var probeAt = 0L
    // the powertrain from the last summary that said (feed thread)
    private var lastPhev: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        GlassStrings.attach(this)
        createChannel()
        startForegroundCompat()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        realMetrics().let { screenW = it.widthPixels; screenH = it.heightPixels }
        ioThread = HandlerThread("glass-widgets-io").also { it.start(); io = Handler(it.looper) }
        feedThread = HandlerThread("glass-widgets-feed").also { it.start(); feed = Handler(it.looper) }
        liveThread = HandlerThread("glass-widgets-live").also { it.start(); liveH = Handler(it.looper) }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GlassStrings.attach(this)
        val s = loadSettings(force = true)
        if (s == null || !s.enabled) {
            Log.i(TAG, "glass widgets disabled - stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!OverlayPermissionChecker.isGranted(this)) {
            Log.w(TAG, "overlay permission not granted - stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        apply(withPending(s))
        if (surface == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startPolling()
        // the Widgets page's "Arrange on screen": once there's data to show
        if (intent?.getStringExtra("action") == "arrange") {
            if (snapshot != null && !offline) enterArrange() else { pendingArrange = true; pendingArrangeAt = SystemClock.elapsedRealtime() }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        GlassStrings.attach(this)
        val s = settings ?: return
        applyTheme(s)
        val dm = realMetrics()
        if (dm.widthPixels != screenW || dm.heightPixels != screenH) {
            screenW = dm.widthPixels; screenH = dm.heightPixels
            // arranged spots, the chooser and arrange mode belong to the old screen shape
            closePicker()
            leaveArrange(null)
            ensureSurface(s)
            orb?.relayout()
        }
        // a rotating screen changes the room the stack has, and where arranged widgets sit
        refreshStack()
        placeWindow(s)
        free?.rescale()
    }

    override fun onDestroy() {
        // work already posted from the io and feed threads must not bring windows back
        destroyed = true
        if (instance === this) instance = null
        running = false
        io?.removeCallbacksAndMessages(null)
        feed?.removeCallbacksAndMessages(null)
        liveH?.removeCallbacksAndMessages(null)
        ioThread?.quitSafely()
        feedThread?.quitSafely()
        liveThread?.quitSafely()
        net.shutdownNow()
        writer.shutdown()
        closePicker()
        unregisterHomeKeys()
        arrange?.let { removeArrange(it) }
        arrange = null
        removeStack()
        free?.release(); free = null
        orb?.release(); orb = null
        surface = null
        GlassWidgets.setDocs(emptyMap())
        ticker.stop()
        super.onDestroy()
    }

    // ── settings ─────────────────────────────────────────────────────────────

    private fun loadSettings(force: Boolean): Settings? = try {
        val cfg = UnifiedConfigManager.loadConfig()
        val section = cfg.optJSONObject(SECTION)
        // tyre pressures show in the unit picked under Notifications, like everywhere else in the app
        val units = UnifiedConfigManager.getTyrePressureUnit()
        // distances and speeds in the unit picked for trips
        val miles = cfg.optJSONObject("tripAnalytics")?.optString("distanceUnit") == "mi"
        val raw = "$units|$miles|$section"
        // read on the io thread and, at start, on main: the text and what it parsed to stay one pair
        synchronized(settingsLock) {
            if (!force && raw == lastSettings) readSettings
            else Settings.from(section, units, miles).also { lastSettings = raw; readSettings = it }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "config unavailable: ${t.message}")
        null
    }

    /** Main thread: create the surface or bring it in line with [s]. */
    private fun apply(s: Settings) {
        if (destroyed) return
        val prev = settings
        settings = s
        env.units = s.units
        val milesChanged = env.miles != s.miles
        env.miles = s.miles
        env.tint = s.tint
        val scaleChanged = prev?.scale != s.scale
        if (scaleChanged) env.scale = s.scale
        val motionChanged = env.reduceMotion != s.reduceMotion
        env.reduceMotion = s.reduceMotion
        applyTheme(s)
        ensureSurface(s)
        ensureOrb(s)
        if (scaleChanged) surface?.rescale()
        if (motionChanged) surface?.envChanged()
        // tint, size, units and motion all reach the floating button's own copy of the state
        orb?.envChanged()
        customRow { it.envChanged() }
        if (prev == null || prev.position != s.position || prev.offsetX != s.offsetX || prev.offsetY != s.offsetY || scaleChanged) placeWindow(s)
        if (prev != null && prev.mode != s.mode) hiddenSent = null
        refreshStack()
        // tyre tags format with the units at bind time, so a units change re-applies the power state
        snapshot?.let { bindAll(it, animate = false, forcePower = prev?.units != s.units, forceUnits = milesChanged) }
        if (prev?.shortcuts != s.shortcuts) bindShortcuts(true)
        surface?.invalidateWidgets()
        customRow { it.invalidateWidgets() }
    }

    private fun applyTheme(s: Settings) {
        val n = if (s.theme == "auto") systemNight() else s.theme == "night"
        if (n == night) return
        night = n
        env.theme = GlassTheme.of(n)
        surface?.themeChanged()
        customRow { it.themeChanged() }
        // custom widgets' colour expressions read the theme: every one runs again
        bindCustom(animate = false, force = true)
        orb?.themeChanged()
        (picker?.getChildAt(0) as? GlassWidgetView)?.themeChanged()
    }

    /** The floating shortcuts button, when chosen: a window of its own, over every app. */
    private fun ensureOrb(s: Settings) {
        if (!s.floating || destroyed) {
            orb?.let { if (pickerFromOrb) closePicker(); it.release() }
            orb = null
            return
        }
        if (orb == null) {
            orb = GlassOrbWindow(this, windowManager, env, ticker, getSharedPreferences(PREFS, MODE_PRIVATE)) { panel ->
                panel.onRun = ::runShortcut
                panel.onEdit = { slot -> openPicker(slot, fromOrb = true) }
                bindShortcuts(false)
            }.also { it.setStale(offline); it.show() }
        }
        refreshOrb()
    }

    // out of the way while reversing, and while the widgets are being arranged
    private fun refreshOrb() {
        val o = orb ?: return
        val hide = arrange != null || snapshot?.reversing == true
        if (hide && pickerFromOrb) closePicker()
        o.setHidden(hide)
    }

    private fun systemNight(): Boolean {
        val mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
        return when (mode) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> true
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    // ── surfaces ─────────────────────────────────────────────────────────────

    /** The stack until the user arranges the widgets, a window per widget after; never while arranging. */
    private fun ensureSurface(s: Settings) {
        if (arrange != null) return
        val wantFree = s.layout.isNotEmpty() && (s.layoutPortrait == null || s.layoutPortrait == portrait())
        if (wantFree && free == null) {
            closePicker()
            removeStack()
            env.scale = s.scale
            free = GlassFreeLayout(this, windowManager, env, ticker) { enterArrange() }.also { surface = it }
            surfaceChanged()
        } else if (!wantFree && host == null) {
            closePicker()
            free?.release(); free = null
            createOverlay()
            surface = host
            surfaceChanged()
        }
    }

    // a fresh surface has fresh widgets: show them and give them everything known so far
    private fun surfaceChanged() {
        hiddenSent = null
        surface?.setStale(offline)
        refreshStack()
        snapshot?.let { bindAll(it, animate = false) }
    }

    private fun createOverlay() {
        val h = GlassWidgetsHost(this, env, ticker) { enterArrange() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                // the shadow margin may run off the screen edge so the glass itself sits at the offset
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        params.title = "GlassWidgets"
        lp = params
        host = h
        settings?.let { placeWindow(it, update = false) }
        try {
            windowManager.addView(h, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
            h.release()
            host = null
            lp = null
        }
    }

    private fun removeStack() {
        removeCustomRow()
        val v = host ?: return
        host = null
        lp = null
        if (surface === v) surface = null
        v.release()
        try { windowManager.removeView(v) } catch (_: Exception) { }
    }

    /** Anchor the stack by its glass, not its window: the widgets' shadow margins are left out of the offsets. */
    private fun placeWindow(s: Settings, update: Boolean = true) {
        val params = lp ?: return
        val d = resources.displayMetrics.density
        val k = d * env.scale
        val ox = s.offsetX * d; val oy = s.offsetY * d
        val left = ox - GlassWidgetView.PAD_L * k; val right = ox - GlassWidgetView.PAD_R * k
        val top = oy - GlassWidgetView.PAD_T * k; val bottom = oy - GlassWidgetView.PAD_B * k
        val centreX = (GlassWidgetView.PAD_R - GlassWidgetView.PAD_L) / 2f * k
        val centreY = (GlassWidgetView.PAD_B - GlassWidgetView.PAD_T) / 2f * k
        val (g, x, y) = when (s.position) {
            "bottom-center" -> Triple(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, centreX, bottom)
            "bottom-right" -> Triple(Gravity.BOTTOM or Gravity.END, right, bottom)
            "top-left" -> Triple(Gravity.TOP or Gravity.START, left, top)
            "top-right" -> Triple(Gravity.TOP or Gravity.END, right, top)
            "left" -> Triple(Gravity.CENTER_VERTICAL or Gravity.START, left, centreY)
            "right" -> Triple(Gravity.CENTER_VERTICAL or Gravity.END, right, centreY)
            else -> Triple(Gravity.BOTTOM or Gravity.START, left, bottom)
        }
        params.gravity = g
        params.x = x.toInt()
        params.y = y.toInt()
        if (update) updateWindow()
        if (customHost != null) placeCustom(s)
    }

    /** The stack takes touches whenever it shows (a long press arranges); hidden, it never blocks the screen. */
    private fun setTouchable(touchable: Boolean) {
        val params = lp ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val next = if (touchable) params.flags and flag.inv() else params.flags or flag
        if (next == params.flags) return
        params.flags = next
        updateWindow()
    }

    private fun updateWindow() {
        val v = host ?: return
        val params = lp ?: return
        try { windowManager.updateViewLayout(v, params) } catch (e: Exception) { Log.w(TAG, "updateViewLayout: ${e.message}") }
    }

    // ── stack mode's custom row ──────────────────────────────────────────────

    /** [ids] in their own row beside the built-ins shown ([beside]); the window goes once the last has left. */
    private fun showCustom(s: Settings, ids: List<String>, beside: List<String>) {
        if (ids.isEmpty()) {
            val h = customHost ?: return
            h.show(ids, s.vertical, stackGravity(s.position))
            main.postDelayed({ if (customHost === h && h.childCount == 0) removeCustomRow() }, GlassWidgetView.EXIT_MS + 120L)
            return
        }
        val h = customHost ?: createCustomRow(s) ?: return
        customBeside = beside
        h.show(ids, s.vertical, stackGravity(s.position))
        fitCustom(s, ids)
        placeCustom(s)
    }

    private fun createCustomRow(s: Settings): GlassWidgetsHost? {
        customScale = s.scale
        val e = GlassEnv().also { it.copyFrom(env); it.scale = customScale }
        val h = GlassWidgetsHost(this, e, ticker) { enterArrange() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        params.title = "GlassWidgetsCustom"
        customHost = h; customLp = params; customEnv = e
        placeCustom(s, update = false)
        try {
            windowManager.addView(h, params)
        } catch (ex: Exception) {
            Log.e(TAG, "custom row addView failed: ${ex.message}")
            h.release()
            customHost = null; customLp = null; customEnv = null
            return null
        }
        // it joins the stack as the stack is: fogged, or hidden
        h.setStale(offline)
        if (env.hidden) h.setHidden(true)
        return h
    }

    private fun removeCustomRow() {
        val v = customHost ?: return
        customHost = null
        customLp = null
        customEnv = null
        customBeside = emptyList()
        v.release()
        try { windowManager.removeView(v) } catch (_: Exception) { }
    }

    /** The custom row's scale: the user's, shrunk (never grown) to fit the screen, as the built-ins' row does. */
    private fun fitCustom(s: Settings, ids: List<String>) {
        val h = customHost ?: return
        val dm = resources.displayMetrics
        val extent = h.extent(ids, s.vertical)
        val room = if (s.vertical) dm.heightPixels / dm.density - 2f * s.offsetY else dm.widthPixels / dm.density - 2f * s.offsetX
        val fit = if (extent > 0f) (room / extent).coerceAtLeast(0.4f) else s.scale
        val next = minOf(s.scale, fit)
        if (next == customScale) return
        customScale = next
        syncCustomEnv()
        h.rescale()
    }

    /**
     * The custom row's window, anchored by its glass one gap past the built-ins' full-size row: above a bottom
     * row, below a top one, the next column beside a side one; where the built-ins sit when none shows.
     */
    private fun placeCustom(s: Settings, update: Boolean = true) {
        val params = customLp ?: return
        val d = resources.displayMetrics.density
        val kb = d * env.scale
        val k = d * customScale
        val beside = customBeside
        val reach = if (beside.isEmpty()) 0f
            else (if (s.vertical) beside.maxOf { GlassWidgets.size(it).first } else beside.maxOf { GlassWidgets.size(it).second }) * kb + GlassWidgetView.GAP * kb
        val ox = s.offsetX * d + (if (s.vertical) reach else 0f)
        val oy = s.offsetY * d + (if (s.vertical) 0f else reach)
        // as placeWindow anchors the built-ins' stack
        val left = ox - GlassWidgetView.PAD_L * k; val right = ox - GlassWidgetView.PAD_R * k
        val top = oy - GlassWidgetView.PAD_T * k; val bottom = oy - GlassWidgetView.PAD_B * k
        val centreX = (GlassWidgetView.PAD_R - GlassWidgetView.PAD_L) / 2f * k
        val centreY = (GlassWidgetView.PAD_B - GlassWidgetView.PAD_T) / 2f * k
        val (g, x, y) = when (s.position) {
            "bottom-center" -> Triple(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, centreX, bottom)
            "bottom-right" -> Triple(Gravity.BOTTOM or Gravity.END, right, bottom)
            "top-left" -> Triple(Gravity.TOP or Gravity.START, left, top)
            "top-right" -> Triple(Gravity.TOP or Gravity.END, right, top)
            "left" -> Triple(Gravity.CENTER_VERTICAL or Gravity.START, left, centreY)
            "right" -> Triple(Gravity.CENTER_VERTICAL or Gravity.END, right, centreY)
            else -> Triple(Gravity.BOTTOM or Gravity.START, left, bottom)
        }
        params.gravity = g
        params.x = x.toInt()
        params.y = y.toInt()
        if (update) customHost?.let { try { windowManager.updateViewLayout(it, params) } catch (e: Exception) { Log.w(TAG, "custom row layout: ${e.message}") } }
    }

    private fun setCustomTouchable(touchable: Boolean) {
        val v = customHost ?: return
        val params = customLp ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val next = if (touchable) params.flags and flag.inv() else params.flags or flag
        if (next == params.flags) return
        params.flags = next
        try { windowManager.updateViewLayout(v, params) } catch (e: Exception) { Log.w(TAG, "custom row layout: ${e.message}") }
    }

    // the custom row's copy of the shared state, at its own scale (its hidden flag stays its own)
    private fun syncCustomEnv() {
        val e = customEnv ?: return
        e.copyFrom(env)
        e.scale = customScale
    }

    // the custom row hears what the built-ins' surface hears, its state copied first
    private inline fun customRow(block: (GlassWidgetsHost) -> Unit) {
        val h = customHost ?: return
        syncCustomEnv()
        block(h)
    }

    private fun realMetrics(): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(it)
    }

    private fun portrait() = realMetrics().let { it.heightPixels > it.widthPixels }

    // ── arrange mode ─────────────────────────────────────────────────────────

    /** Every listed widget over a dimmed screen, to be dragged where it should live. */
    private fun enterArrange() {
        // with core unreachable nothing arranged could be saved
        if (arrange != null || destroyed || offline) return
        val s = settings ?: return
        // arranging is a parked job: while driving or reversing the request is dropped
        if (env.driving || snapshot?.reversing == true) { pendingArrange = false; return }
        pendingArrange = false
        closePicker()
        // the custom widgets on screen come along, after the built-ins
        val shownCustom = s.custom.filter { GlassWidgets.doc(it) != null }
        // arranged widgets keep their real size: the row's shrink-to-fit doesn't carry over; a placed
        // widget not showing now (charging while not charging) keeps its place too
        val start = if (host != null) null else (free?.placed() ?: surface?.shells())?.filterKeys { it in s.widgets || it in shownCustom }
        removeStack()
        free?.release(); free = null
        env.scale = s.scale
        env.hidden = false
        arrangeFrom = s.widgets
        customFrom = shownCustom
        val layer = GlassArrangeLayer(this, env, ticker, s.widgets + shownCustom, s.layout.mapValues { it.value.size }, start,
            stackRects = { sizes -> stackRects(s, sizes) },
            // the tray's previews show live data too
            onBind = { snapshot?.let { bindAll(it, animate = false) } },
            onDone = { shells, sizes, ids, reset -> finishArrange(shells, sizes, ids, reset) },
            onCancel = { leaveArrange(null) },
            // the tray's saved and ready-made widgets, read on the io thread when it first opens
            askCatalog = { arrange?.let { readCatalog(it) } })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // focusable (unlike the widgets' own windows), so the Back key reaches it and cancels
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { title = "GlassWidgetsArrange"; gravity = Gravity.TOP or Gravity.START }
        try {
            windowManager.addView(layer, params)
        } catch (e: Exception) {
            Log.e(TAG, "arrange addView failed: ${e.message}")
            layer.release()
            settings?.let { ensureSurface(it) }
            return
        }
        arrange = layer
        surface = layer
        refreshOrb()
        if (!homeKeysOn) homeKeysOn = try { registerReceiver(homeKeys, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)); true } catch (_: Exception) { false }
        layer.setStale(offline)
        snapshot?.let { bindAll(it, animate = false) }
        bindShortcuts(false)
    }

    private fun finishArrange(shells: Map<String, RectF>, sizes: Map<String, Float>, ids: List<String>, reset: Boolean) {
        if (arrange == null) return
        val s = settings ?: return
        val dm = realMetrics()
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        // each widget hangs from the screen corner nearest it, kept as fractions so it survives a resolution change
        val layout = if (reset) emptyMap() else shells.mapValues { (id, r) ->
            val right = r.centerX() > w / 2f; val bottom = r.centerY() > h / 2f
            GlassFreeLayout.Spot(round4((if (right) r.right else r.left) / w), round4((if (bottom) r.bottom else r.top) / h), right, bottom, roundSize(sizes[id] ?: 1f))
        }
        val json = JSONObject()
        for ((id, sp) in layout) {
            val o = JSONObject().put("x", sp.x.toDouble()).put("y", sp.y.toDouble()).put("r", sp.right).put("b", sp.bottom)
            if (sp.size != 1f) o.put("s", sp.size.toDouble())
            json.put(id, o)
        }
        val portrait = dm.heightPixels > dm.widthPixels
        // only what was added or taken off here goes onto the list as it is now, so an edit made on the
        // settings page meanwhile stands, and a widget taken off and put back keeps its place
        val removed = arrangeFrom.filter { it !in ids }.toSet()
        val added = ids.filter { it !in arrangeFrom && !GlassWidgets.isCustom(it) }
        val next = s.widgets.filter { it !in removed } + added.filter { it !in s.widgets }
        // an earlier Done's list not yet stored goes again with this write
        val widgets = if (next != s.widgets || pendingLayout?.value?.widgets != null) next else null
        // the custom widgets' own list, the same way
        val removedCustom = customFrom.filter { it !in ids }.toSet()
        val nextCustom = s.custom.filter { it !in removedCustom } + ids.filter { GlassWidgets.isCustom(it) && it !in customFrom && it !in s.custom }
        val custom = if (nextCustom != s.custom || pendingLayout?.value?.custom != null) nextCustom else null
        val data = JSONObject().put("layout", json).put("layoutPortrait", portrait)
        if (widgets != null) data.put("widgets", JSONArray(widgets))
        if (custom != null) data.put("custom", JSONArray(custom))
        val pending = Pending(Arrangement(layout, portrait, widgets, custom)).also { pendingLayout = it }
        // the daemon polls the blind-spot radar only while the power widget is listed
        if (("power" in next) != ("power" in arrangeFrom) || powerSyncFor != null) powerSyncFor = pending
        layoutWrites++
        save(data, onStored = { syncPower(pending) }) { refused ->
            if (--layoutWrites == 0) layoutAckAt = SystemClock.elapsedRealtime()
            if (refused && pendingLayout === pending) pendingLayout = null
        }
        leaveArrange(layout, widgets, custom)
    }

    // tells the daemon once the arrangement that changed the power widget is stored
    private fun syncPower(stored: Pending<Arrangement>) {
        if (powerSyncFor !== stored) return
        powerSyncFor = null
        background { http("POST", "/api/widgets/sync", "{}") }
    }

    private fun round4(v: Float) = (v.coerceIn(0f, 1f) * 10_000f).roundToInt() / 10_000f

    private fun roundSize(v: Float) = ((v * 100f).roundToInt() / 100f).coerceIn(GlassArrangeLayer.MIN_SIZE, GlassArrangeLayer.MAX_SIZE)

    /** Back to the stack or the free layout; the arrange layer fades out over the new widgets. */
    private fun leaveArrange(layout: Map<String, GlassFreeLayout.Spot>?, widgets: List<String>? = null, custom: List<String>? = null) {
        val layer = arrange ?: return
        arrange = null
        surface = null
        refreshOrb()
        unregisterHomeKeys()
        // a close from outside (driving off, a rotation) must not leave its buttons live while it fades
        layer.dismiss()
        // its window goes whatever happens next; the arranged windows are checked against their spots after
        main.postDelayed({ removeArrange(layer); free?.recheckSoon() }, ARRANGE_FADE_MS)
        settings?.let { s ->
            val next = if (layout != null) s.copy(layout = layout, layoutPortrait = portrait(), widgets = widgets ?: s.widgets, custom = custom ?: s.custom).also { settings = it } else s
            // docs read while arranging apply now; the tray's others go
            val d = deferredDocs
            deferredDocs = null
            if (d != null || GlassWidgets.hasDocs()) register(d, keepDocs(next.custom))
            ensureSurface(next)
        }
    }

    private fun unregisterHomeKeys() {
        if (!homeKeysOn) return
        homeKeysOn = false
        try { unregisterReceiver(homeKeys) } catch (_: Exception) { }
    }

    private fun removeArrange(layer: GlassArrangeLayer) {
        layer.release()
        try { windowManager.removeView(layer) } catch (_: Exception) { }
    }

    /**
     * The stack the settings describe at full size, in screen px, where arrange mode starts from the row.
     * A row that doesn't fit wraps onto another row (above, for the bottom positions), so nothing overlaps.
     */
    private fun stackRects(s: Settings, sizes: Map<String, SizeF>): Map<String, RectF> {
        val out = stackRects(s, s.widgets.filter { it in sizes }, sizes, 0f)
        if (s.custom.isEmpty()) return out
        val custom = s.custom.filter { it in sizes }
        if (custom.isEmpty()) return out
        // the custom widgets' own rows, one gap past the built-ins', as their window stacks them
        val dm = realMetrics()
        val d = resources.displayMetrics.density
        val ox = s.offsetX * d; val oy = s.offsetY * d
        val gap = GlassWidgetView.GAP * d * env.scale
        val r = out.values
        val beyond = when {
            r.isEmpty() -> 0f
            s.position == "left" -> r.maxOf { it.right } - ox + gap
            s.position == "right" -> dm.widthPixels - ox - r.minOf { it.left } + gap
            s.position.startsWith("top") -> r.maxOf { it.bottom } - oy + gap
            else -> dm.heightPixels - oy - r.minOf { it.top } + gap
        }
        out.putAll(stackRects(s, custom, sizes, beyond))
        return out
    }

    // [ids] stacked from the anchored edges, [beyond] px further from them along the stacking axis
    private fun stackRects(s: Settings, ids: List<String>, sizes: Map<String, SizeF>, beyond: Float): LinkedHashMap<String, RectF> {
        val dm = realMetrics()
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val d = resources.displayMetrics.density
        val gap = GlassWidgetView.GAP * d * env.scale
        val out = LinkedHashMap<String, RectF>()
        if (ids.isEmpty()) return out
        val ox = s.offsetX * d + (if (s.vertical) beyond else 0f); val oy = s.offsetY * d + (if (s.vertical) 0f else beyond)
        if (s.vertical) {
            val total = ids.sumOf { sizes.getValue(it).height.toDouble() }.toFloat() + gap * (ids.size - 1)
            var y = (h - total) / 2f
            for (id in ids) {
                val z = sizes.getValue(id)
                val x = if (s.position == "right") w - ox - z.width else ox
                out[id] = RectF(x, y, x + z.width, y + z.height)
                y += z.height + gap
            }
        } else {
            val room = w - 2f * ox
            val rows = ArrayList<MutableList<String>>()
            var used = 0f
            for (id in ids) {
                val zw = sizes.getValue(id).width
                if (rows.isEmpty() || (used + gap + zw > room && rows.last().isNotEmpty())) { rows.add(mutableListOf()); used = -gap }
                rows.last().add(id); used += gap + zw
            }
            val top = s.position.startsWith("top")
            var edge = if (top) oy else h - oy
            for (row in rows) {
                val total = row.sumOf { sizes.getValue(it).width.toDouble() }.toFloat() + gap * (row.size - 1)
                val rowH = row.maxOf { sizes.getValue(it).height }
                var x = when (s.position) {
                    "bottom-center" -> (w - total) / 2f
                    "bottom-right", "top-right" -> w - ox - total
                    else -> ox
                }
                for (id in row) {
                    val z = sizes.getValue(id)
                    val y = if (top) edge else edge - z.height
                    out[id] = RectF(x, y, x + z.width, y + z.height)
                    x += z.width + gap
                }
                edge = if (top) edge + rowH + gap else edge - rowH - gap
            }
        }
        return out
    }

    // ── what is on screen ────────────────────────────────────────────────────

    /**
     * Listed widgets, minus charging while not charging (if chosen) and any widget core has never given data for;
     * then the custom and ready-made widgets whose doc has been read.
     */
    private fun visibleIds(s: Settings): List<String> {
        val charging = snapshot?.charging == true
        val builtIn = s.widgets.filter {
            when (it) {
                "charging" -> !(s.chargingAuto && !charging)
                "battery" -> seenBattery
                "trip", "drive" -> seenTrip
                else -> true
            }
        }
        return if (s.custom.isEmpty()) builtIn else builtIn + s.custom.filter { GlassWidgets.doc(it) != null }
    }

    private fun refreshStack() {
        val s = settings ?: return
        refreshOrb()
        if (arrange != null) return
        // with no custom widget listed, no doc is kept
        if (s.custom.isEmpty() && GlassWidgets.hasDocs()) register(null, keepDocs(s.custom))
        val sf = surface ?: return
        // nothing until core has answered once, so no widget ever shows made-up numbers
        if (snapshot == null) { showIds(s, emptyList()); setTouchable(false); return }
        val ids = visibleIds(s)
        showIds(s, ids)
        val need = customNeeds(s, ids)
        wantVehicleState = "controls" in ids
        wantCalibrated = "battery" in ids || (need and NEED_RANGE) != 0
        wantAutos = "shortcuts" in ids || s.floating
        val hidden = homeHidden || snapshot?.reversing == true
        sf.setHidden(hidden)
        customHost?.let { it.setHidden(hidden); setCustomTouchable(!hidden) }
        if (hidden) closePicker()
        val wasHidden = feedHidden
        feedHidden = homeHidden
        // back on screen after a slow hidden poll: fresh figures now rather than in up to ten seconds
        if (wasHidden && !homeHidden) feed?.post { pollSummary(); if (wantCalibrated) refreshCalibrated() }
        setTouchable(!hidden && (if (customHost == null) ids.isNotEmpty() else ids.any { !GlassWidgets.isCustom(it) }))
        // the live poll feeds the drive trace, drive dynamics and the power widget's blind-spot zones;
        // custom widgets that read live signals get it once a second (or with whichever runs faster)
        liveDynamics = "dynamics" in ids
        livePedals = (need and NEED_PEDALS) != 0
        customRoad = (need and NEED_ROAD) != 0
        val liveCustom = (need and NEED_LIVE) != 0
        liveMs = if (liveDynamics) LIVE_DYNAMICS_MS else if ("drive" in ids || (liveCustom && !("power" in ids && s.showBs))) LIVE_MS else LIVE_BS_MS
        setWantLive(("drive" in ids || liveDynamics || ("power" in ids && s.showBs) || liveCustom) && !hidden && env.driving)
        wire()
    }

    // what the shown custom widgets read beyond the summary: live samples, the pedals, RoadSense, the learned range
    private fun customNeeds(s: Settings, ids: List<String>): Int {
        if (s.custom.isEmpty() || !GlassWidgets.hasDocs()) return 0
        var need = 0
        for (id in ids) {
            val d = GlassWidgets.doc(id) ?: continue
            if (d.needsLive) need = need or NEED_LIVE
            for (k in d.signals) need = need or when {
                k in PEDAL_KEYS -> NEED_LIVE or NEED_PEDALS
                k.startsWith("hazard_") -> NEED_ROAD
                k == "calibrated_range_km" -> NEED_RANGE
                else -> 0
            }
        }
        return need
    }

    private fun showIds(s: Settings, ids: List<String>) {
        host?.let { h ->
            // the stack holds the built-ins alone; custom widgets get a row of their own beside it
            val builtIn = if (s.custom.isEmpty()) ids else ids.filter { !GlassWidgets.isCustom(it) }
            h.show(builtIn, s.vertical, stackGravity(s.position))
            fitScale(s, builtIn)
            if (builtIn !== ids || customHost != null) showCustom(s, ids.filter { GlassWidgets.isCustom(it) }, builtIn)
        }
        free?.let { f ->
            f.setSpots(s.layout)
            f.show(ids)
        }
    }

    private fun wire() {
        val sf = surface ?: return
        sf.widget<GlassControlsView>("controls")?.onCommand = ::command
        sf.widget<GlassShortcutsView>("shortcuts")?.let { v ->
            v.onRun = ::runShortcut
            v.onEdit = ::openPicker
        }
    }

    /** The window's anchored edges, which the host packs its widgets against. */
    private fun stackGravity(position: String) = when (position) {
        "bottom-center" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        "bottom-right" -> Gravity.BOTTOM or Gravity.END
        "top-left" -> Gravity.TOP or Gravity.START
        "top-right" -> Gravity.TOP or Gravity.END
        "left" -> Gravity.CENTER_VERTICAL or Gravity.START
        "right" -> Gravity.CENTER_VERTICAL or Gravity.END
        else -> Gravity.BOTTOM or Gravity.START
    }

    /** Shrink (never grow) the user's scale just enough for the stack to fit the screen with the same margin both sides. */
    private fun fitScale(s: Settings, ids: List<String>) {
        val h = host ?: return
        val dm = resources.displayMetrics
        val extent = h.extent(ids, s.vertical)
        val room = if (s.vertical) dm.heightPixels / dm.density - 2f * s.offsetY else dm.widthPixels / dm.density - 2f * s.offsetX
        val fit = if (extent > 0f) (room / extent).coerceAtLeast(0.4f) else s.scale
        val next = minOf(s.scale, fit)
        if (next == env.scale) return
        env.scale = next
        h.rescale()
        placeWindow(s)
    }

    // ── binding (main thread) ────────────────────────────────────────────────

    private fun onSnapshot(snap: GlassWidgetsFeed.Snapshot) {
        if (destroyed) return
        val prev = snapshot
        snapshot = snap
        val wasOffline = offline
        if (offline) { offline = false; env.coreOffline = false; surface?.setStale(false); orb?.setStale(false); customRow { it.setStale(false) } }
        val drivingChanged = env.driving != snap.driving
        env.driving = snap.driving
        feedDriving = snap.driving
        if (drivingChanged && snap.driving) closePicker()
        // driving off or reversing puts arranged widgets back where they were
        if (arrange != null && (snap.driving || snap.reversing)) leaveArrange(null)
        val newData = (!seenBattery && snap.battery != null) || (!seenTrip && snap.trip != null)
        if (snap.battery != null) seenBattery = true
        if (snap.trip != null) seenTrip = true
        // a new trip starts its regen share afresh
        snap.trip?.let { t ->
            if (t.live && (!tripLiveSeen || t.km + 0.3f < tripKmSeen)) { regenKwh = 0.0; driveKwh = 0.0; lastLiveAt = 0L }
            tripLiveSeen = t.live; tripKmSeen = t.km
        }
        // back from offline too: the live poll stopped while core was away, and the range is worth a fresh read
        if (prev == null || wasOffline || newData || drivingChanged || prev.charging != snap.charging || prev.reversing != snap.reversing) refreshStack()
        if (wasOffline) feed?.post { if (wantCalibrated) refreshCalibrated() }
        if (drivingChanged) { surface?.envChanged(); orb?.envChanged(); customRow { it.envChanged() } }
        // right after a lock command the summary can still carry the old state; don't flip back to it.
        // A missing value keeps the last known one (the vehicle-state call fills it when parked).
        if (SystemClock.elapsedRealtime() > lockHoldUntil) {
            snap.locked?.let { controls = controls.copy(locked = it) }
            // no whole-car state: an unlocked driver door means unlocked, a locked one voids a stale "unlocked"
            if (snap.locked == null) snap.driverLocked?.let { d ->
                controls = controls.copy(driverLocked = d, locked = when {
                    !d -> false
                    controls.locked == false -> null
                    else -> controls.locked
                })
            }
        }
        bindAll(snap, animate = prev != null)
        // asked for while core was down: only soon after, not minutes later over whatever is on screen
        if (pendingArrange) { if (SystemClock.elapsedRealtime() - pendingArrangeAt <= PENDING_ARRANGE_MS) enterArrange() else pendingArrange = false }
    }

    private fun bindAll(snap: GlassWidgetsFeed.Snapshot, animate: Boolean, forcePower: Boolean = false, forceUnits: Boolean = false) {
        val sf = surface ?: return
        val s = settings ?: return
        // with core's range failing read after read, the car's own figure is better than an old one
        if (rangeJson != null && rangeFails >= CALIBRATED_FAILS) { rangeJson = null; calibratedKm = null }
        rangeJson?.let { calibratedKm = GlassWidgetsFeed.calibrated(it, snap.phev) }
        snap.battery?.let { sf.widget<GlassBatteryView>("battery")?.bind(it.copy(calibratedKm = calibratedKm), animate, forceUnits) }
        sf.widget<GlassChargingView>("charging")?.bind(snap.chargingState, animate)
        snap.trip?.let { sf.widget<GlassTripView>("trip")?.bind(it, animate, forceUnits) }
        GlassWidgetsFeed.drive(snap, regenPct())?.let { sf.widget<GlassDriveView>("drive")?.bind(it, animate, forceUnits) }
        env.powerLive = snap.packKw != null
        sf.widget<GlassPowerView>("power")?.bind(GlassWidgetsFeed.power(snap, driveLayout(s, snap), s.showBs, freshBs()), animate, forcePower)
        sf.widget<GlassControlsView>("controls")?.bind(controls, animate)
        bindRoad(animate, forceUnits)
        bindShortcuts(animate)
        bindCustom(animate, force = forcePower || forceUnits)
    }

    /**
     * The shown custom widgets (in arrange mode also the tray's): every signal mapped once per reading and
     * handed to each, which re-evaluates only what changed. [sampleAt] adds a live sample to their sparks.
     */
    private fun bindCustom(animate: Boolean, force: Boolean = false, sampleAt: Long = -1L) {
        if (!GlassWidgets.hasDocs()) return
        val sf = surface ?: return
        val s = settings ?: return
        syncCustomEnv()
        val ids: Collection<String> = if (arrange != null) GlassWidgets.customIds else s.custom
        if (ids.isEmpty()) return
        var values: Map<String, Any?>? = null
        for (id in ids) {
            val v = (customHost?.widget<GlassWidgetView>(id) ?: sf.widget<GlassWidgetView>(id)) as? GlassCustomView ?: continue
            val m = values ?: CustomSignals.values(snapshot, road, freshLive(), calibratedKm).also { values = it }
            if (sampleAt >= 0L) v.addSample(sampleAt, m)
            v.bind(m, animate, force)
        }
    }

    // the live poll's last reading while it runs; once it stops, the summary's figures stand in
    private fun freshLive(): GlassWidgetsFeed.Live? =
        customLive?.takeIf { wantLive && SystemClock.uptimeMillis() - customLiveAt < LIVE_GAP_MS }

    private fun bindRoad(animate: Boolean, force: Boolean = false) {
        val snap = snapshot ?: return
        val r = road ?: GlassWidgetsFeed.road(null, 0L)
        val region = when (Locale.getDefault().country) { "US" -> "us"; "CN" -> "cn"; else -> "eu" }
        val terrain = if (settings?.terrain != false) snap.terrain else null
        surface?.widget<GlassRoadSenseView>("roadsense")?.bind(
            GlassRoadSenseView.State(snap.roadActive, r.feed, r.clear, r.kind, r.severity, r.distanceM, r.cluster, snap.keepLeft, region, terrain), animate, force)
    }

    // A key shows what was saved with it until the automation list arrives; then the list decides
    // whether it still exists, its current name and icon, and whether it's turned off.
    private fun bindShortcuts(animate: Boolean) {
        val s = settings ?: return
        // the widget and the floating button's panel show the same three keys
        val widget = surface?.widget<GlassShortcutsView>("shortcuts")
        val panel = orb?.panel
        if (widget == null && panel == null) return
        val list = autos
        val slots = s.shortcuts.map { sc ->
            if (sc == null) return@map GlassShortcutsView.Slot(null, "", "plus")
            val a = list?.firstOrNull { it.id == sc.id }
            when {
                list == null -> GlassShortcutsView.Slot(sc.id, sc.name, sc.glyph, missing = sc.id in removedIds)
                a == null -> GlassShortcutsView.Slot(sc.id, sc.name, sc.glyph, missing = true)
                else -> GlassShortcutsView.Slot(sc.id, a.name, a.glyph, off = a.mode == "disabled")
            }
        }
        widget?.bind(slots, animate)
        panel?.bind(slots, animate)
    }

    private fun onOffline(minutes: Int) {
        env.lastSeenMin = minutes
        // no core, no samples: the live poll stops until a summary arrives again
        setWantLive(false)
        if (!offline) { offline = true; env.coreOffline = true; surface?.setStale(true); orb?.setStale(true); customRow { it.setStale(true) } }
        else { surface?.invalidateWidgets(); customRow { it.invalidateWidgets() } }
    }

    /** The configured motor layout, or the one this car has shown: a motor counts once it turns while driving. */
    private fun driveLayout(s: Settings, snap: GlassWidgetsFeed.Snapshot): GlassPowerView.Drive {
        when (s.motors) {
            "awd" -> return GlassPowerView.Drive.AWD
            "fwd" -> return GlassPowerView.Drive.FWD
            "rwd" -> return GlassPowerView.Drive.RWD
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var front = prefs.getBoolean(KEY_FRONT, false)
        var rear = prefs.getBoolean(KEY_REAR, false)
        if (snap.driving) {
            val f = !front && GlassWidgetsFeed.motorSeen(snap.frontRpm)
            val r = !rear && GlassWidgetsFeed.motorSeen(snap.rearRpm)
            if (f || r) {
                front = front || f; rear = rear || r
                prefs.edit().putBoolean(KEY_FRONT, front).putBoolean(KEY_REAR, rear).apply()
            }
        }
        return if (front && rear) GlassPowerView.Drive.AWD else if (rear) GlassPowerView.Drive.RWD else GlassPowerView.Drive.FWD
    }

    // ── live drive samples ───────────────────────────────────────────────────

    private val liveTick = object : Runnable {
        override fun run() {
            if (!running || !wantLive) { liveScheduled = false; return }
            pollLive()
            liveH?.postDelayed(this, liveMs)
        }
    }

    private fun setWantLive(want: Boolean) {
        // a pause in the samples (hidden, reversing, parked) counts no energy across it
        if (!want) lastLiveAt = 0L
        wantLive = want
        if (want) liveH?.post { if (!liveScheduled) { liveScheduled = true; liveTick.run() } }
    }

    private fun pollLive() {
        // a fixed-rate read: a slow core mustn't stretch the gaps between samples
        val path = if (liveDynamics || livePedals) "/api/widgets/live?dyn=1" else "/api/widgets/live"
        val (code, text) = http("GET", path, "", readTimeoutMs = LIVE_TIMEOUT_MS)
        if (code != 200) return
        val v = try { GlassWidgetsFeed.live(JSONObject(text)) } catch (_: Throwable) { return }
        val at = SystemClock.uptimeMillis()
        main.post { onLive(at, v) }
    }

    // the regen share is this overlay's own sum over the trip it has watched
    private fun onLive(at: Long, v: GlassWidgetsFeed.Live) {
        // a sample that was out when the loop stopped belongs to before the pause
        if (!wantLive) return
        val kw = v.kw
        // energy only between close readings: across a pause (hidden, reversing) nothing was measured
        val gap = if (lastLiveAt > 0L) at - lastLiveAt else -1L
        val dtH = if (gap in 1..LIVE_GAP_MS) gap / 3_600_000.0 else 0.0
        lastLiveAt = at
        if (!kw.isNaN() && snapshot?.trip?.live == true) {
            if (kw > 0f) driveKwh += kw * dtH else regenKwh += -kw * dtH
        }
        val sf = surface ?: return
        // the trace keeps its once-a-second spacing however fast the poll runs
        if (at - lastDriveSampleAt >= DRIVE_SAMPLE_MS || at < lastDriveSampleAt) {
            lastDriveSampleAt = at
            sf.widget<GlassDriveView>("drive")?.addSample(at, kw, v.kmh)
        }
        sf.widget<GlassDynamicsView>("dynamics")?.addSample(at, v.kmh, kw, v.accel, v.brake, v.rpm)
        if (v.hasBs) {
            val prev = freshBs()
            liveBs = v; liveBsAt = at
            // a zone lighting or clearing shows now, not at the next summary
            if (prev == null || prev.bsLeft != v.bsLeft || prev.bsRight != v.bsRight || prev.bsAny != v.bsAny) {
                val snap = snapshot; val s = settings
                if (snap != null && s != null) sf.widget<GlassPowerView>("power")?.bind(GlassWidgetsFeed.power(snap, driveLayout(s, snap), s.showBs, v), true)
            }
        }
        if (settings?.custom?.isEmpty() == false && GlassWidgets.hasDocs()) {
            customLive = v; customLiveAt = at
            bindCustom(animate = true, sampleAt = at)
        }
    }

    private fun freshBs(): GlassWidgetsFeed.Live? = liveBs?.takeIf { SystemClock.uptimeMillis() - liveBsAt < LIVE_BS_FRESH_MS }

    private fun regenPct(): Int? = if (driveKwh >= 0.3) (regenKwh / driveKwh * 100.0).roundToInt().coerceIn(0, 99) else null

    // ── shortcuts ────────────────────────────────────────────────────────────

    private fun runShortcut(slot: Int, id: String, done: (Boolean?) -> Unit) {
        background {
            // the list on the key can be a minute old, and core accepts a run of a turned-off automation
            // as a no-op; a fresh list catches that (and a removed one) before anything is sent
            val asked = autosAsked.incrementAndGet()
            val list = fetchAutos()
            val a = list?.firstOrNull { it.id == id }
            if (list != null && (a == null || a.mode == "disabled")) {
                main.post { onAutos(asked, list); done(if (a == null) null else false) }
                return@background
            }
            list?.let { main.post { onAutos(asked, it) } }
            val enc = try { URLEncoder.encode(id, "UTF-8").replace("+", "%20") } catch (_: Throwable) { id }
            val (code, _) = http("POST", "/api/automations/test/$enc", "{}")
            main.post {
                done(when (code) { in 200..299 -> true; 404 -> null; else -> false })
                if (code == 404) {
                    // core says it's gone: the key and the chooser show it removed whatever an older list says
                    removedIds += id
                    autosApplied = autosAsked.get() + 1
                    val listed = autos?.any { it.id == id } == true
                    autos = autos?.filter { it.id != id }
                    bindShortcuts(true)
                    if (listed && picker != null && pickerSlot >= 0) showPicker(pickerSlot)
                    refreshAutos(force = true)
                }
            }
        }
    }

    private fun fetchAutos(): List<GlassPickerSheet.Item>? {
        val (code, text) = http("GET", "/api/widgets/automations", "")
        return if (code == 200) try { GlassWidgetsFeed.automations(JSONObject(text)) } catch (_: Throwable) { null } else null
    }

    /** Main thread: a fresh automation list, [asked] in the order it was requested; false when it was too old to apply. */
    private fun onAutos(asked: Int, list: List<GlassPickerSheet.Item>): Boolean {
        if (asked < autosApplied) return false
        autosApplied = asked
        removedIds.clear()
        lastAutos = SystemClock.elapsedRealtime()
        autosFailed = false
        val changed = list != autos
        autos = list
        if (changed) bindShortcuts(true)
        // a chooser that opened before the list arrived shows it now
        if (picker != null && pickerSlot >= 0 && changed) showPicker(pickerSlot)
        return true
    }

    private fun refreshAutos(force: Boolean = false) {
        if (destroyed || (autosInFlight > 0 && !force)) return
        if (!force && SystemClock.elapsedRealtime() - lastAutos < AUTOS_MS) return
        autosInFlight++
        background {
            val asked = autosAsked.incrementAndGet()
            val list = fetchAutos()
            main.post {
                autosInFlight--
                if (list == null) {
                    // retried sooner than the usual minute; a chooser still waiting for its list says it
                    // failed, once (rebuilding it on every retry would blink it and drop a tap mid-way)
                    lastAutos = SystemClock.elapsedRealtime() - AUTOS_MS + AUTOS_RETRY_MS
                    autosFailed = true
                    if (picker != null && pickerSlot >= 0 && autos == null && !pickerFailed && autosInFlight == 0) showPicker(pickerSlot)
                    return@post
                }
                // thrown away as older (a 404 since) with nothing else coming: ask again rather than wait for the retry
                if (!onAutos(asked, list) && autosInFlight == 0 && picker != null && pickerSlot >= 0) refreshAutos(force = true)
            }
        }
    }

    private fun openPicker(slot: Int, fromOrb: Boolean = false) {
        if (env.driving || destroyed || offline) return
        pickerSlot = slot
        pickerFromOrb = fromOrb
        // the floating button's panel stays open under its chooser
        if (fromOrb) orb?.holdOpen = true
        refreshAutos(force = true)
        showPicker(slot)
    }

    /** The chooser sits over the key it edits, above the widget if there's room, else below. */
    private fun showPicker(slot: Int) {
        removePicker()
        val s = settings ?: return
        val o = orb
        val shell: RectF
        val view: GlassShortcutsView
        if (pickerFromOrb) {
            view = o?.panel?.takeIf { o.isOpen } ?: return
            shell = RectF(o.panelGlass)
        } else {
            val sf = surface ?: return
            shell = sf.shells()["shortcuts"] ?: return
            view = sf.widget<GlassShortcutsView>("shortcuts") ?: return
        }
        val k = resources.displayMetrics.density * env.scale
        val failed = autos == null && autosFailed && autosInFlight == 0
        val sheet = GlassPickerSheet(this, env, ticker, gs(R.string.glass_shortcut_n, slot + 1), autos, failed,
            s.shortcuts.getOrNull(slot)?.id,
            onPick = { item -> setShortcut(slot, Shortcut(item.id, item.name, item.glyph)); closePicker() },
            onRemove = { setShortcut(slot, null); closePicker() })
        val dm = realMetrics()
        val w = sheet.baseW * k; val h = sheet.baseH * k
        val margin = 12f * resources.displayMetrics.density
        val cx = shell.left + view.slotCenterFraction(slot) * shell.width()
        val left = (cx - w / 2f).coerceIn(margin, max(margin, dm.widthPixels - margin - w))
        val above = shell.top - margin - h
        // below the widget it stays clear of a navigation bar or a car's dock, which draw over it
        val bottomClear = max(margin, PICKER_BOTTOM_CLEAR * resources.displayMetrics.density)
        val top = if (above >= margin) above else min(shell.bottom + margin, dm.heightPixels - bottomClear - h).coerceAtLeast(margin)
        val frame = PickerFrame(sheet, (left - GlassWidgetView.PAD_L * k).roundToInt(), (top - GlassWidgetView.PAD_T * k).roundToInt())
        frame.addView(sheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        // full screen and touchable: the chooser is modal, so a touch beside it never reaches the app below
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "GlassWidgetsPicker"
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager.addView(frame, params)
        } catch (e: Exception) {
            Log.w(TAG, "picker addView: ${e.message}")
            sheet.release()
            return
        }
        picker = frame
        pickerFailed = failed
        sheet.enter(0L)
    }

    /** Holds the sheet at its screen spot (px); a press anywhere off the sheet's glass closes it. */
    private inner class PickerFrame(private val sheet: GlassPickerSheet, private val sx: Int, private val sy: Int) :
        FrameLayout(this@GlassWidgetsOverlayService) {
        private val loc = IntArray(2)
        private val pt = FloatArray(2)
        private val inverse = android.graphics.Matrix()

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            getLocationOnScreen(loc)
            val x = sx - loc[0]; val y = sy - loc[1]
            sheet.layout(x, y, x + sheet.measuredWidth, y + sheet.measuredHeight)
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                // the press mapped through the sheet's own transform: its entry motion moves the glass for a moment
                pt[0] = e.x - sheet.left; pt[1] = e.y - sheet.top
                if (!sheet.matrix.isIdentity && sheet.matrix.invert(inverse)) inverse.mapPoints(pt)
                if (!sheet.onGlass(pt[0], pt[1])) { closePicker(); return true }
            }
            return super.dispatchTouchEvent(e)
        }
    }

    private fun closePicker() {
        pickerSlot = -1
        if (pickerFromOrb) orb?.holdOpen = false
        pickerFromOrb = false
        removePicker()
    }

    private fun removePicker() {
        val f = picker ?: return
        picker = null
        (f.getChildAt(0) as? GlassWidgetView)?.release()
        try { windowManager.removeView(f) } catch (_: Exception) { }
    }

    private fun setShortcut(slot: Int, sc: Shortcut?) {
        val s = settings ?: return
        val next = s.shortcuts.toMutableList()
        if (slot !in next.indices) return
        next[slot] = sc
        settings = s.copy(shortcuts = next)
        bindShortcuts(true)
        val arr = JSONArray()
        for (x in next) arr.put(x?.let { JSONObject().put("id", it.id).put("name", it.name).put("glyph", it.glyph) } ?: JSONObject.NULL)
        val pending = Pending(next.toList()).also { pendingShortcuts = it }
        shortcutWrites++
        save(JSONObject().put("shortcuts", arr)) { refused ->
            if (--shortcutWrites == 0) shortcutAckAt = SystemClock.elapsedRealtime()
            if (refused && pendingShortcuts === pending) pendingShortcuts = null
        }
    }

    /**
     * Writes keys of the widgets' config section through core, which owns the file; sibling keys are kept.
     * A write core couldn't be reached for (restarting, say) is tried again a couple of times. [done] runs
     * on the main thread at the end; true means it wasn't saved, so the saved value should show again.
     */
    private fun save(data: JSONObject, onStored: (() -> Unit)? = null, done: (refused: Boolean) -> Unit) {
        if (destroyed) return
        val body = JSONObject().put("section", SECTION).put("data", data).toString()
        try {
            writer.execute {
                var code = NOT_SENT
                var text = ""
                for (attempt in 0 until SAVE_TRIES) {
                    if (attempt > 0) try { Thread.sleep(SAVE_RETRY_MS * attempt) } catch (_: InterruptedException) { break }
                    val r = http("POST", "/api/settings/unified", body)
                    code = r.first; text = r.second
                    // only a write that never reached core goes again: one with no answer may be saved already
                    if (code != NOT_SENT || destroyed) break
                }
                // core answers a write it couldn't store with 200 and {"success": false}
                val stored = code in 200..299 && !(try { GlassWidgetsFeed.failed(JSONObject(text)) } catch (_: Throwable) { false })
                if (!stored) Log.w(TAG, "widgets config save failed: $code")
                // no answer (0) leaves it to the read-back; a refusal, or nothing sent, shows the saved value
                main.post { done(code != 0 && !stored); if (stored) onStored?.invoke() }
            }
        } catch (_: RejectedExecutionException) { }
    }

    /**
     * The config read back, with each write of ours core hasn't stored yet laid over it. Once every write
     * of a key has answered, the file decides: at once when it carries ours, else after a short grace (a
     * read taken before the save lands), since someone else (the Widgets page) may have written since.
     */
    private fun withPending(s: Settings): Settings {
        val now = SystemClock.elapsedRealtime()
        var out = s
        pendingLayout?.let {
            val a = it.value
            val answered = layoutWrites == 0
            val stored = s.layout == a.layout && s.layoutPortrait == a.portrait && (a.widgets == null || s.widgets == a.widgets) &&
                (a.custom == null || s.custom == a.custom)
            // a write that got no answer but did land: the read-back says so
            if (stored) syncPower(it)
            if ((answered && (stored || now - layoutAckAt > ACK_GRACE_MS)) || now - it.at > PENDING_MAX_MS) pendingLayout = null
            else out = out.copy(layout = a.layout, layoutPortrait = a.portrait, widgets = a.widgets ?: out.widgets, custom = a.custom ?: out.custom)
        }
        pendingShortcuts?.let {
            val answered = shortcutWrites == 0
            if ((answered && (s.shortcuts == it.value || now - shortcutAckAt > ACK_GRACE_MS)) || now - it.at > PENDING_MAX_MS) pendingShortcuts = null
            else out = out.copy(shortcuts = it.value)
        }
        return out
    }

    private fun background(block: () -> Unit) {
        if (destroyed) return
        try { net.execute(block) } catch (_: RejectedExecutionException) { }
    }

    // ── commands ─────────────────────────────────────────────────────────────

    private fun command(cmd: GlassControlsView.Command, done: (Boolean) -> Unit) {
        val group = when (cmd) {
            GlassControlsView.Command.LOCK, GlassControlsView.Command.UNLOCK -> "lock"
            GlassControlsView.Command.CLIMATE_ON, GlassControlsView.Command.CLIMATE_OFF -> "climate"
            GlassControlsView.Command.BOOT -> "boot"
            GlassControlsView.Command.FLASH -> "flash"
            else -> "windows"
        }
        if (!commandsInFlight.add(group)) { done(false); return }
        val (path, body) = when (cmd) {
            GlassControlsView.Command.LOCK -> "/api/vehicle/lock" to "{}"
            GlassControlsView.Command.UNLOCK -> "/api/vehicle/unlock" to "{}"
            GlassControlsView.Command.CLIMATE_ON -> "/api/vehicle/climate" to "{\"action\":\"power_on\"}"
            GlassControlsView.Command.CLIMATE_OFF -> "/api/vehicle/climate" to "{\"action\":\"power_off\"}"
            GlassControlsView.Command.BOOT -> "/api/vehicle/trunk" to "{\"action\":\"open\"}"
            GlassControlsView.Command.FLASH -> "/api/vehicle/flash" to "{}"
            GlassControlsView.Command.WINDOWS_VENT -> "/api/vehicle/window" to "{\"action\":\"vent\"}"
            GlassControlsView.Command.WINDOWS_OPEN -> "/api/vehicle/window" to "{\"area\":0,\"command\":1}"
            GlassControlsView.Command.WINDOWS_CLOSED -> "/api/vehicle/window" to "{\"area\":0,\"command\":2}"
        }
        background {
            val (code, text) = http("POST", path, body, readTimeoutMs = COMMAND_TIMEOUT_MS)
            val ok = code in 200..299 && try {
                val o = JSONObject(text)
                o.optBoolean("success", true) && o.optBoolean("commandSuccess", true)
            } catch (_: Throwable) { true }
            main.post {
                commandsInFlight.remove(group)
                done(ok)
                if (ok) applyCommand(cmd)
            }
            // the car takes a moment to report the new state, and windows take a while to travel; the
            // second read lands after the hold, so it can correct a command the car didn't carry out.
            // Not while driving: the heavy read would hold up the summary on the same thread.
            feed?.postDelayed({ if (!feedDriving) refreshVehicleState() }, 2500)
            feed?.postDelayed({ if (!feedDriving) refreshVehicleState() }, STATE_HOLD_MS + 1000)
        }
    }

    private fun applyCommand(cmd: GlassControlsView.Command) {
        val now = SystemClock.elapsedRealtime()
        when (cmd) {
            GlassControlsView.Command.LOCK, GlassControlsView.Command.UNLOCK -> lockHoldUntil = now + LOCK_HOLD_MS
            GlassControlsView.Command.CLIMATE_ON, GlassControlsView.Command.CLIMATE_OFF -> climateHoldUntil = now + STATE_HOLD_MS
            GlassControlsView.Command.WINDOWS_VENT, GlassControlsView.Command.WINDOWS_OPEN, GlassControlsView.Command.WINDOWS_CLOSED -> windowsHoldUntil = now + STATE_HOLD_MS
            else -> {}
        }
        controls = when (cmd) {
            GlassControlsView.Command.LOCK -> controls.copy(locked = true)
            GlassControlsView.Command.UNLOCK -> controls.copy(locked = false)
            GlassControlsView.Command.CLIMATE_ON -> controls.copy(climate = true)
            GlassControlsView.Command.CLIMATE_OFF -> controls.copy(climate = false)
            GlassControlsView.Command.WINDOWS_VENT -> controls.copy(windows = GlassControlsView.Windows.VENT)
            GlassControlsView.Command.WINDOWS_OPEN -> controls.copy(windows = GlassControlsView.Windows.OPEN)
            GlassControlsView.Command.WINDOWS_CLOSED -> controls.copy(windows = GlassControlsView.Windows.CLOSED)
            else -> controls
        }
    }

    // ── custom widgets' docs ─────────────────────────────────────────────────

    /**
     * Io thread: the listed custom and ready-made widgets' docs, read again only when core's store says it
     * changed (its version) or a widget joins the list. With none listed nothing is read.
     */
    private fun syncDocs(s: Settings) {
        if (s.custom.isEmpty()) { docsList = null; docsAsked = emptySet(); return }
        if (s.custom === docsList && s.customVersion == docsVersion) return
        if (s.customVersion == docsVersion && docsAsked.containsAll(s.custom)) { docsList = s.custom; return }
        val found = HashMap<String, Loaded>()
        readBundled(s.custom, found)
        val ok = readStore(s.custom, found)
        // a store that can't be read is tried on the next ticks, then left until it changes
        if (!ok && ++docsFails < DOCS_TRIES) return
        docsFails = 0
        docsVersion = s.customVersion
        docsList = s.custom
        docsAsked = s.custom.toSet()
        // unread, the custom docs already shown stay as they are
        val asked = if (ok) docsAsked else docsAsked.filterTo(HashSet()) { !it.startsWith(GlassWidgets.CUSTOM) }
        main.post { applyDocs(Docs(asked, found)) }
    }

    /** Core's store, every entry or just [only]'s; false when it couldn't be read (an absent store is empty). */
    private fun readStore(only: Collection<String>?, out: MutableMap<String, Loaded>): Boolean {
        if (only != null && only.none { it.startsWith(GlassWidgets.CUSTOM) }) return true
        val text = try {
            val f = File(ScratchRoot.path(STORE_FILE))
            if (!f.exists()) return true
            if (f.length() > STORE_MAX_BYTES) return false
            f.readText()
        } catch (t: Throwable) {
            Log.w(TAG, "custom widgets unreadable: ${t.message}")
            return false
        }
        val list = try { JSONObject(text).optJSONArray("widgets") } catch (_: Throwable) { return false }
        if (list == null) return true
        for (i in 0 until min(list.length(), STORE_MAX_ENTRIES)) {
            val e = list.optJSONObject(i) ?: continue
            val id = GlassWidgets.CUSTOM + e.optString("id")
            val wanted = if (only != null) id in only else Settings.CUSTOM_ID.matches(id)
            if (!wanted) continue
            val doc = e.optJSONObject("doc") ?: continue
            parseDoc(id, doc.toString())?.let { out[id] = it }
        }
        return true
    }

    /** The ready-made widgets the APK ships, every one (in the index's order) or just [only]'s. */
    private fun readBundled(only: Collection<String>?, out: MutableMap<String, Loaded>) {
        val stems = ArrayList<String>()
        if (only != null) {
            for (id in only) if (id.startsWith(GlassWidgets.BUNDLED)) stems += id.substring(GlassWidgets.BUNDLED.length)
        } else {
            try {
                val index = JSONArray(assets.open("$TEMPLATES/index.json").bufferedReader().use { it.readText() })
                for (i in 0 until index.length()) {
                    val file = index.optJSONObject(i)?.optString("file") ?: continue
                    if (file.endsWith(".json")) stems += file.removeSuffix(".json")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ready-made widgets unavailable: ${t.message}")
            }
        }
        for (stem in stems) {
            val id = GlassWidgets.BUNDLED + stem
            // the id's pattern keeps the name to a plain file in the templates folder
            if (!Settings.CUSTOM_ID.matches(id)) continue
            val text = try { assets.open("$TEMPLATES/$stem.json").bufferedReader().use { it.readText() } } catch (_: Throwable) { continue }
            parseDoc(id, text)?.let { out[id] = it }
        }
    }

    // a doc that doesn't validate is skipped
    private fun parseDoc(id: String, text: String): Loaded? {
        if (text.length > DOC_MAX_CHARS) return null
        return try { Loaded(text, CustomDoc.parse(text, id)) } catch (t: Throwable) { Log.w(TAG, "widget $id skipped: ${t.message}"); null }
    }

    /** Main thread: docs read on the io thread. A shown widget whose design changed goes and comes back with it. */
    private fun applyDocs(docs: Docs) {
        if (destroyed) return
        // arranging keeps the docs it opened with until it closes
        if (arrange != null) { deferredDocs = docs; return }
        val s = settings ?: return
        val changed = register(docs, keepDocs(s.custom))
        if (changed.isNotEmpty() && snapshot != null) {
            val ids = visibleIds(s)
            if (ids.any { it in changed }) showIds(s, ids.filter { it !in changed })
        }
        refreshStack()
        bindCustom(animate = false)
    }

    // The ids whose doc may stay: those listed here and those the config file lists. The io thread reads a
    // listed doc only once, so one dropped while our write is pending would be lost should that write fail.
    private fun keepDocs(listed: List<String>): Collection<String> {
        val file = readSettings?.custom.orEmpty()
        return if (file.isEmpty()) listed else listed + file
    }

    /**
     * Main thread: the registry takes what [docs] read (an unchanged text keeps its doc) and loses the ids it
     * asked for and didn't find; other docs stay only for ids in [keep]. Returns the ids whose doc changed.
     */
    private fun register(docs: Docs?, keep: Collection<String>): Set<String> {
        val next = HashMap<String, CustomDoc>()
        val changed = HashSet<String>()
        for (id in keep) GlassWidgets.doc(id)?.let { next[id] = it }
        if (docs != null) for (id in docs.asked) {
            val l = docs.found[id]
            val old = GlassWidgets.doc(id)
            if (l == null) { next.remove(id); continue }
            if (old != null && docTexts[id] == l.text) { next[id] = old; continue }
            if (old != null) changed += id
            next[id] = l.doc
            docTexts[id] = l.text
        }
        docTexts.keys.retainAll(next.keys)
        GlassWidgets.setDocs(next)
        return changed
    }

    /** Arrange mode's tray catalog: every saved widget, then every ready-made one, read and parsed on the io thread. */
    private fun readCatalog(layer: GlassArrangeLayer) {
        io?.post {
            val found = LinkedHashMap<String, Loaded>()
            readStore(null, found)
            readBundled(null, found)
            main.post { onCatalog(layer, found) }
        }
    }

    // main thread: the catalog joins the registry (docs on screen stay as they are while arranging) and the tray
    private fun onCatalog(layer: GlassArrangeLayer, found: Map<String, Loaded>) {
        if (destroyed || arrange !== layer) return
        val next = HashMap<String, CustomDoc>()
        for (id in GlassWidgets.customIds) GlassWidgets.doc(id)?.let { next[id] = it }
        for ((id, l) in found) if (id !in next) { next[id] = l.doc; docTexts[id] = l.text }
        GlassWidgets.setDocs(next)
        layer.addCatalog(found.keys.toList())
    }

    // ── polling: settings + RoadSense + visibility (io), core summary (feed) ──

    private fun startPolling() {
        if (running) return
        running = true
        io?.post(object : Runnable {
            override fun run() {
                if (!running) return
                ioTick()
                io?.postDelayed(this, POLL_MS)
            }
        })
        feed?.post(object : Runnable {
            override fun run() {
                if (!running) return
                pollSummary()
                val now = SystemClock.elapsedRealtime()
                if (wantVehicleState && !feedDriving && now - lastVehicleState > VEHICLE_STATE_MS) refreshVehicleState()
                if (wantCalibrated && !feedHidden && now - lastCalibrated > CALIBRATED_MS) refreshCalibrated()
                if (wantAutos && !feedHidden) main.post { refreshAutos() }
                feed?.postDelayed(this, if (feedHidden) POLL_HIDDEN_MS else if (feedDriving) POLL_DRIVING_MS else POLL_PARKED_MS)
            }
        })
    }

    private fun ioTick() {
        val s = loadSettings(force = false) ?: return
        if (!s.enabled) {
            if (++disabledReads >= 2) main.post { stopSelf() }
            return
        }
        disabledReads = 0
        main.post {
            // until core has our own latest write, the file still holds the value before it
            val next = withPending(s)
            if (next != settings) apply(next)
        }
        // after the settings that list them: docs land once their ids are known on main
        syncDocs(s)
        if ("roadsense" in s.widgets || customRoad) {
            val o = try {
                UnifiedConfigManager.loadConfig().optJSONObject(ROADSENSE_SECTION)?.optJSONObject(ROADSENSE_KEY)
            } catch (_: Throwable) { null }
            val r = GlassWidgetsFeed.road(o, System.currentTimeMillis())
            val key = "${r.feed}|${r.clear}|${r.kind}|${r.severity}|${r.distanceM}|${r.cluster}"
            if (key != lastRoadKey) {
                lastRoadKey = key
                main.post { road = r; bindRoad(animate = true); bindCustom(animate = true) }
            }
        }
        val hide = s.mode == "home" && !homeVisible()
        if (hide != hiddenSent) {
            hiddenSent = hide
            main.post { homeHidden = hide; refreshStack() }
        }
    }

    private fun pollSummary() {
        val (code, text) = http("GET", "/api/launcher/v1/summary", "")
        val now = SystemClock.elapsedRealtime()
        // core answers some failures with 200 and {"success": false}: no data, a failed poll
        val snap = if (code == 200) try { JSONObject(text).takeIf { !GlassWidgetsFeed.failed(it) }?.let { GlassWidgetsFeed.parse(it, lastPhev) } } catch (t: Throwable) { null } else null
        snap?.let { lastPhev = it.phev }
        if (snap != null) {
            lastOk = now
            main.post { onSnapshot(snap) }
        } else if (lastOk > 0L && now - lastOk > OFFLINE_AFTER_MS) {
            val minutes = ((now - lastOk) / 60_000L).toInt().coerceAtLeast(1)
            main.post { onOffline(minutes) }
        }
    }

    // The full vehicle-state call is heavy on core: at start, once a minute while parked, and after commands.
    private fun refreshVehicleState() {
        lastVehicleState = SystemClock.elapsedRealtime()
        val (code, text) = http("GET", "/api/vehicle/state", "")
        if (code != 200) return
        val v = try { JSONObject(text).takeIf { !GlassWidgetsFeed.failed(it) }?.let { GlassWidgetsFeed.vehicle(it) } } catch (_: Throwable) { null } ?: return
        main.post {
            // a reading taken while a command is still settling (glass mid-travel) doesn't undo it
            val now = SystemClock.elapsedRealtime()
            controls = controls.copy(
                locked = if (now > lockHoldUntil) v.locked ?: controls.locked else controls.locked,
                climate = if (now > climateHoldUntil) v.climate ?: controls.climate else controls.climate,
                cabinC = v.cabinC ?: controls.cabinC,
                windows = if (now > windowsHoldUntil) v.windows ?: controls.windows else controls.windows,
            )
            surface?.widget<GlassControlsView>("controls")?.bind(controls, true)
        }
    }

    // OverDrive's learned range moves with the charge, so once a minute is plenty; it reads the trip database.
    private fun refreshCalibrated() {
        lastCalibrated = SystemClock.elapsedRealtime()
        val (code, text) = http("GET", "/api/trips/range", "")
        val o = if (code == 200) try { JSONObject(text).takeIf { !GlassWidgetsFeed.failed(it) } } catch (_: Throwable) { null } else null
        main.post {
            // a failed read keeps the last figure for a while: the learned range doesn't vanish because core was busy;
            // with core away altogether (offline) the read says nothing about the range
            if (o == null) { if (!offline) rangeFails++; return@post }
            rangeFails = 0
            rangeJson = o
            val snap = snapshot ?: return@post
            val v = GlassWidgetsFeed.calibrated(o, snap.phev)
            if (v == calibratedKm) return@post
            calibratedKm = v
            snap.battery?.let { surface?.widget<GlassBatteryView>("battery")?.bind(it.copy(calibratedKm = v), true) }
            bindCustom(animate = true)
        }
    }

    /**
     * True when a launcher's home screen is on top. The daemon's live top task decides (shell uid sees
     * other apps' tasks; this process can't), read again right after any window event and every few
     * seconds, since a return home doesn't always announce itself. The accessibility reading stands in
     * only while the daemon doesn't answer.
     */
    private fun homeVisible(): Boolean {
        probeIfDue()
        daemonHome()?.let { return it }
        return accessibilityHome()
    }

    private fun probeIfDue() {
        val now = SystemClock.elapsedRealtime()
        val event = KeyMapDispatcher.windowEventAt()
        val since = now - probeAt
        // at once after a window event, twice more while it settles, else every few seconds
        val due = (event > probeAt && since >= EVENT_PROBE_MS) || (now - event < EVENT_BURST_MS && since >= BURST_PROBE_MS) ||
            since >= IDLE_PROBE_MS
        if (due) probeForeground()
    }

    /**
     * From the daemon's last answer; null when it has none. A launcher's declared home activity on top is
     * the home screen. So is the first activity of the launcher's home task: a home activity that hands
     * straight on to another (a trampoline) leaves that one first, while the app list and the launcher's
     * settings open on top of it. Without the task's type the class alone decides, as the fallback does.
     */
    private fun daemonHome(): Boolean? {
        if (SystemClock.elapsedRealtime() - probeAt > PROBE_FRESH_MS) return null
        val pkg = probePkg ?: return null
        if (pkg !in homePackages()) return false
        val cls = probeCls ?: return true
        val homes = homeActivities()
        if (homes.isEmpty() || cls in homes) { homeActivitySeen = true; return true }
        return when (probeHomeTask) {
            true -> cls == probeBase
            false -> false
            null -> !homeActivitySeen
        }
    }

    /**
     * The accessibility service's last activity, for when the daemon doesn't answer. A launcher's other
     * activities count as off home only once its home activity has been seen, so a launcher whose
     * reported class never matches keeps the widgets shown; nothing known at all shows them too.
     */
    private fun accessibilityHome(): Boolean {
        val top = KeyMapDispatcher.topApp() ?: return true
        val pkg = top.pkg ?: return true
        if (pkg !in homePackages()) return false
        val cls = top.cls ?: return true
        val homes = homeActivities()
        if (homes.isEmpty() || cls in homes) { homeActivitySeen = true; return true }
        return !homeActivitySeen
    }

    /** Asks the daemon which activity is on top of the main screen; the answer is kept until the next ask. */
    private fun probeForeground() {
        // the io thread also reads settings and RoadSense: a slow daemon mustn't hold them up
        val (code, text) = http("GET", "/api/widgets/foreground", "", readTimeoutMs = PROBE_TIMEOUT_MS)
        val o = if (code == 200) try { JSONObject(text) } catch (_: Throwable) { null } else null
        probeAt = SystemClock.elapsedRealtime()
        probePkg = o?.let { str(it, "pkg") }
        probeCls = o?.let { str(it, "cls") }
        probeBase = o?.let { str(it, "base") }
        probeHomeTask = if (o == null || !o.has("homeTask") || o.isNull("homeTask")) null else o.optBoolean("homeTask")
    }

    private fun str(o: JSONObject, key: String) = o.optString(key, "").takeIf { !o.isNull(key) && it.isNotEmpty() }

    private var homes: Set<String>? = null
    private var homeActs: Set<String>? = null
    private var homesAt = 0L
    private var homeActivitySeen = false

    private fun homePackages(): Set<String> {
        resolveHomes()
        return homes ?: emptySet()
    }

    /** Class names of the home-screen activities, including what an activity-alias points at. */
    private fun homeActivities(): Set<String> {
        resolveHomes()
        return homeActs ?: emptySet()
    }

    private fun resolveHomes() {
        val now = System.currentTimeMillis()
        if (homes != null && now - homesAt < 60_000L) return
        val pkgs = mutableSetOf("com.byd.launcher", "com.overdrive.launcher")
        val acts = mutableSetOf<String>()
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                // a negative priority marks a boot-time stand-in (Settings' FallbackHome), not a launcher
                .filter { it.priority >= 0 }
                .forEach { ri ->
                    val ai = ri.activityInfo ?: return@forEach
                    ai.packageName?.let { pkgs.add(it) }
                    // an alias reports its target's class in accessibility events, so keep both
                    ai.name?.let { acts.add(it) }
                    ai.targetActivity?.let { acts.add(it) }
                }
        } catch (_: Throwable) { }
        pkgs.remove("android")
        homes = pkgs
        homeActs = acts
        homesAt = now
    }

    private fun http(method: String, path: String, body: String, readTimeoutMs: Int = 8000): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://127.0.0.1:$PORT$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = min(3000, readTimeoutMs)
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (method == "POST") { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json") }
            // set up before connecting (nothing can be after); a refused or timed-out connect sent nothing
            try { conn.connect() } catch (t: Throwable) { return NOT_SENT to "" }
            if (method == "POST") conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } catch (t: Throwable) {
            0 to ""
        } finally {
            conn?.disconnect()
        }
    }

    // ── foreground notification ──────────────────────────────────────────────

    private fun startForegroundCompat() {
        val n = buildNotification()
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(NOTIFICATION_ID, n)
            }
        } catch (t: Throwable) {
            try { startForeground(NOTIFICATION_ID, n) } catch (t2: Throwable) { Log.e(TAG, "startForeground failed: ${t2.message}") }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, gs(R.string.glass_channel_name), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle(gs(R.string.glass_notif_title))
            .setContentText(gs(R.string.glass_notif_text))
            .setSmallIcon(R.drawable.ic_widgets)
            .setOngoing(true)
            .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
            .build()
    }

    companion object {
        private const val TAG = "GlassWidgets"
        private const val SECTION = "glassWidgets"
        private const val ROADSENSE_SECTION = "roadSense"
        private const val ROADSENSE_KEY = "overlayState"
        private const val CHANNEL = "glass_widgets_overlay"
        private const val NOTIFICATION_ID = 9988
        private const val PREFS = "glass_widgets"
        private const val KEY_FRONT = "motorFront"
        private const val KEY_REAR = "motorRear"
        private const val POLL_MS = 500L
        private const val POLL_PARKED_MS = 4000L
        private const val POLL_DRIVING_MS = 2000L
        private const val POLL_HIDDEN_MS = 10_000L
        private const val LIVE_MS = 1000L
        private const val LIVE_DYNAMICS_MS = 250L
        private const val LIVE_BS_MS = 500L
        private const val LIVE_BS_FRESH_MS = 1500L
        private const val DRIVE_SAMPLE_MS = 900L
        private const val OFFLINE_AFTER_MS = 20_000L
        private const val VEHICLE_STATE_MS = 60_000L
        private const val CALIBRATED_MS = 60_000L
        /** This many failed reads in a row (about five minutes on screen) retire the calibrated figure. */
        private const val CALIBRATED_FAILS = 5
        /** An arrange request made while core was down still opens if data arrives within this. */
        private const val PENDING_ARRANGE_MS = 20_000L
        /** After every write of a key has answered, how long a read-back without it can still be a stale read. */
        private const val ACK_GRACE_MS = 10_000L
        /** http()'s code for a request that never reached core (the connect failed). */
        private const val NOT_SENT = -1
        private const val SAVE_TRIES = 3
        private const val SAVE_RETRY_MS = 2000L
        private const val LIVE_TIMEOUT_MS = 2000
        /** Live readings further apart than a poll plus two slow answers have a pause between them, which counts no energy. */
        private const val LIVE_GAP_MS = LIVE_MS + 2L * LIVE_TIMEOUT_MS + 500L
        private const val AUTOS_MS = 60_000L
        private const val AUTOS_RETRY_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 1000
        /** The daemon is asked this soon after a window event, every [BURST_PROBE_MS] for [EVENT_BURST_MS], else every [IDLE_PROBE_MS]. */
        private const val EVENT_PROBE_MS = 250L
        private const val BURST_PROBE_MS = 500L
        private const val EVENT_BURST_MS = 1500L
        private const val IDLE_PROBE_MS = 3000L
        /** An answer older than this (the io thread held up) isn't used. */
        private const val PROBE_FRESH_MS = 8000L
        private const val LOCK_HOLD_MS = 8000L
        private const val STATE_HOLD_MS = 15_000L
        /** The longest our own config write wins over what's read back, should core never store it. */
        private const val PENDING_MAX_MS = 60_000L
        private const val ARRANGE_FADE_MS = 300L
        private const val PICKER_BOTTOM_CLEAR = 110f
        /** Lock, unlock and flash can go through the cloud, which answers in up to 30 s. */
        private const val COMMAND_TIMEOUT_MS = 35_000
        private val PORT = com.overdrive.app.daemon.CameraDaemon.HTTP_PORT
        /** Core's store of custom widgets, under the scratch root; read only when its version changes. */
        private const val STORE_FILE = "overdrive_custom_widgets.json"
        private const val STORE_MAX_BYTES = 1L shl 20
        private const val STORE_MAX_ENTRIES = 64
        private const val DOC_MAX_CHARS = 32 * 1024
        private const val DOCS_TRIES = 3
        /** The ready-made widgets in the APK, which the widget builder also serves as its templates. */
        private const val TEMPLATES = "web/shared/widget-builder/templates"
        // what the shown custom widgets read beyond the summary
        private const val NEED_LIVE = 1
        private const val NEED_PEDALS = 2
        private const val NEED_ROAD = 4
        private const val NEED_RANGE = 8
        /** Signals the live poll carries only when it asks for the pedals too. */
        private val PEDAL_KEYS = setOf("accel_pct", "brake_pct", "motor_front_rpm", "motor_rear_rpm")

        @Volatile private var instance: GlassWidgetsOverlayService? = null

        /** The app's language pick reaches activities only: re-resolve the widgets' strings now.
         *  Labels fed by live data pick the new language up on the next poll. */
        fun relocalizeIfRunning() {
            val s = instance ?: return
            s.main.post {
                if (s.destroyed) return@post
                GlassStrings.attach(s)
                s.surface?.themeChanged()
                s.customRow { it.themeChanged() }
                s.orb?.themeChanged()
                (s.picker?.getChildAt(0) as? GlassWidgetView)?.themeChanged()
            }
        }

        /** The app's theme pick reaches activities only: re-resolve the widgets' theme now. */
        fun refreshThemeIfRunning() {
            val s = instance ?: return
            s.main.post { if (!s.destroyed) s.settings?.let { s.applyTheme(it) } }
        }

        /** App-side reconcile with the saved master toggle (boot / keepalive path). */
        fun syncWithConfig(context: Context): Boolean {
            val enabled = try {
                UnifiedConfigManager.loadConfig().optJSONObject(SECTION)?.optBoolean("enabled", false) ?: false
            } catch (t: Throwable) {
                false
            }
            if (!enabled) {
                context.stopService(Intent(context, GlassWidgetsOverlayService::class.java))
                return false
            }
            if (!OverlayPermissionChecker.isGranted(context)) {
                Log.w(TAG, "no overlay permission - not starting")
                return false
            }
            context.startForegroundService(Intent(context, GlassWidgetsOverlayService::class.java))
            return true
        }
    }
}
