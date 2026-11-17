package com.overdrive.app.ui.fragment

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.overdrive.app.R
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.view.ClusterViewMirrorClient
import com.overdrive.app.ui.view.ProjectionBoundsGeometry
import com.overdrive.app.ui.view.ProjectionBoundsView
import com.overdrive.app.ui.widget.AppToast
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Projection screen (Vehicle category, native car-app only — no web surface).
 *
 * Cast an installed app onto the driver-cluster ("fission") display, see a LIVE mirror of
 * it on the head unit inside a DRAGGABLE + RESIZABLE bounding rectangle, and tap/swipe the
 * mirror to control the otherwise non-touch cluster.
 *
 * ## How the pixels get here
 * The daemon (uid 2000) composites the cluster into a TextureView Surface owned by this
 * fragment. This fragment owns the pane geometry, forwards touch, and keeps the daemon's
 * projection mapping synchronized with view/surface lifecycle changes.
 *
 * ## Lifecycle / no-leak contract
 * Mirror starts on onResume (while a cast is active) and STOPS on onPause. All daemon calls
 * run on a background executor; UI touches are re-posted to the main thread and DROPPED once
 * the view is destroyed (via [alive]) so a slow in-flight call can never crash after detach
 * or be rejected after executor shutdown. View refs + observer listeners are cleared in
 * onDestroyView. The chosen bounds are persisted so they survive leaving/returning.
 */
class ProjectionFragment : Fragment() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val mirrorIo: ExecutorService = Executors.newSingleThreadExecutor()
    private val clusterInputIo: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val mirrorSessionId = ClusterViewMirrorClient.newSessionId()

    private var spinnerApps: Spinner? = null
    private var btnCast: Button? = null
    private var btnStop: Button? = null
    private var btnAdjust: Button? = null
    private var statusText: TextView? = null
    private var statusDot: View? = null
    private var appToast: AppToast? = null
    private var lastStickyError: String? = null
    private var stage: View? = null
    private var bounds: ProjectionBoundsView? = null
    private var progress: ProgressBar? = null
    private var scaleGroup: MaterialButtonToggleGroup? = null
    private var presetGroup: MaterialButtonToggleGroup? = null
    private var aspectLockSwitch: MaterialSwitch? = null
    private var autoStartSwitch: MaterialSwitch? = null
    private val speedOverlaySwitch = SurveillanceSwitch(SPEED_OVERLAY_KEY)
    private val adasHideSwitch = SurveillanceSwitch(ADAS_HIDE_KEY)
    private var transportGroup: MaterialButtonToggleGroup? = null
    private var transportStatus: TextView? = null
    private var projectionPathGroup: MaterialButtonToggleGroup? = null
    private var projectionTargetGroup: MaterialButtonToggleGroup? = null
    private var projectionPathStatus: TextView? = null
    private var projectionGeometryControls: View? = null
    private var diLink5ProjectionControls: View? = null
    private val projectionSliders = LinkedHashMap<String, SeekBar>()
    private val projectionSliderValues = LinkedHashMap<String, TextView>()
    private var texture: TextureView? = null
    private var btnFullscreen: MaterialButton? = null
    private var scroll: View? = null
    private var content: LinearLayout? = null
    private var stageCard: View? = null
    private var dock: View? = null
    private var settings: View? = null
    private var hud: View? = null
    private var emptyState: View? = null

    // Full screen hides the rail, app bar and settings so the stage can take the window.
    private var theater = false
    // Which state the button's icon was last set for; the XML starts it on "enter".
    private var fullscreenButtonTheater = false
    private var contentPadding = IntArray(4)
    private val theaterBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { setTheater(false) }
    }
    private var busy = false
    private var castInFlight = false
    @DrawableRes private var statusDotRes = R.drawable.status_dot_neutral
    private val hudFade = Runnable { hud?.animate()?.alpha(0f)?.setDuration(HUD_FADE_MS)?.start() }
    // Touched only on [io], so it needs no lock.
    private val appIcons = HashMap<String, Drawable>()

    // The live Surface from [texture] (created on SurfaceTexture available/resized). The
    // daemon composites the cluster into it. Held only while the surface is valid.
    @Volatile private var mirrorSurface: Surface? = null
    private val surfaceLock = Any()
    @Volatile private var surfaceGeneration = 0L

    private val appPackages = ArrayList<String>()

    @Volatile private var casting = false
    @Volatile private var layoutActive = false
    @Volatile private var layoutBusy = false
    private var layoutGeneration = 0L
    @Volatile private var diLink5ModeRequired = false
    // The package ACTUALLY on the cluster, per the daemon (empty when none). The resize box acts on
    // THIS app, not on the spinner selection — the two diverge the moment the user browses the app
    // list during a live cast. All geometry persistence/restore is keyed to this while casting, so a
    // drag can never reshape the cast app to a different app's remembered rect.
    @Volatile private var castPackage = ""
    @Volatile private var castSessionGeneration = 0L
    @Volatile private var resolvedProjectionTransport = ""
    @Volatile private var activeProjectionPath = "none"
    private var activeHostedBounds: IntArray? = null
    // The package the box's CURRENT rect actually describes — set by restoreWindowFor (the only
    // thing that binds a rect to an app) and read by geometryPackage(). Empty until the first bind.
    // This is what keeps a persist from being keyed to a browsed spinner pick or to a stale
    // post-Stop state while the box still shows the previous app's rect.
    @Volatile private var boxPackage = ""

    @Volatile private var mirrorMode = MODE_STOPPED
    @Volatile private var alive = false
    @Volatile private var mirrorRequested = false
    @Volatile private var mirrorRequestGeneration = 0L
    private var adjusting = false

    // True when the layout failed to inflate (stale resources after an in-place update) and
    // we're showing the degraded fallback view; skips all setup so nothing touches the
    // (absent) real views or fires needless work.
    private var inflateFailed = false

    @Volatile private var scaleMode = SCALE_FIT
    // Real cluster panel size (from the daemon status poll) — drives the box aspect-lock + the
    // daemon's letterbox. The mirror buffer is VIEW-sized (not this), so the daemon letterboxes
    // the cluster into the box rect. Defaults to the Seal cluster until the first poll refines it.
    @Volatile private var panelW = 1920
    @Volatile private var panelH = 720
    // Suppress the toggle/switch change listeners while we set their state programmatically
    // (restore, conflict-dialog revert) so we don't re-fire the handler / loop the dialog.
    private var suppressScaleListener = false
    private var suppressPresetListener = false
    private var suppressAspectListener = false
    private var suppressAutoStartListener = false
    private var suppressTransportListener = false
    private var suppressProjectionPathListener = false
    @Volatile private var configuredProjectionPath = "auto"
    @Volatile private var configuredProjectionTarget = "standard"
    // The persisted projection.clusterTransport choice (auto/legacy/dilink5),
    // refreshed from the status poll so the control tracks out-of-band edits.
    @Volatile private var configuredTransport = "auto"
    // True while a transport write is in flight: a status poll fetched BEFORE
    // the write landed must not snap the control back to the stale value.
    @Volatile private var transportWriteInFlight = false
    // Cached sibling state so the conflict dialog can decide without a blocking read: whether
    // RoadSense's map auto-project is currently on. Refreshed by fetchAutoStartState().
    @Volatile private var roadSenseAutoProject = false

    // Swallow the next onItemSelected echo from a programmatic spinner change (adapter set
    // or setSelection). -1 = no echo pending (pass through normally).
    private var pendingSpinnerEcho = -1

    // The box rect (view px) the mirror is projected into — the daemon's projection dest.
    // Written on the main thread (wireBounds/armMirror); read off-main by postTap/postSwipe on
    // [io]. @Volatile publishes each wholesale reassignment (the array is never mutated in
    // place) so a touch worker always sees a consistent, current rect — never a torn one.
    @Volatile private var lastPaneRect = intArrayOf(-1, -1, -1, -1)

    private var statusPoll: Runnable? = null
    private var stageLayoutListener: View.OnLayoutChangeListener? = null
    private var surfaceRestart: Runnable? = null
    private val mirrorAttachRunning = AtomicBoolean(false)
    private val mirrorAttachAgain = AtomicBoolean(false)
    // True while a LIVE-drag cluster-resize POST is in flight. Intermediate drag frames are
    // dropped (not queued) while set, so a slow daemon can't accumulate a backlog of stale rects
    // on the single HTTP executor and lag behind the finger. Commit frames ignore this flag.
    private val resizeInFlight = AtomicBoolean(false)
    // Incremented per authoritative resize; only the newest commit's reply may move the box.
    @Volatile private var commitSeq = 0L

    // Mirror Binder broadcast from the daemon (DI5 refuses its ServiceManager registration).
    // Registered with DUMP as the sender permission, which only the uid-2000 daemon holds here.
    private val mirrorBinderReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            ClusterViewMirrorClient.offerBinder(
                intent?.extras?.getBinder(ClusterViewMirrorClient.EXTRA_BINDER))
            if (alive && mirrorRequested && casting
                && !ClusterViewMirrorClient.isSessionActive(mirrorSessionId)) armMirrorIfIdle()
        }
    }
    private var mirrorBinderReceiverRegistered = false
    @Volatile private var lastMirrorPublishRequestMs = 0L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        inflateFailed = false
        // Guard the inflate. After an in-place app update (pm install -r), API-29 can leave a
        // process with a stale resource/dex table; inflating this screen's custom views
        // (bounds view, toggle group, switch) then throws InflateException/NotFoundException.
        // Unguarded, that crashes the whole app on opening Projection (the "must reinstall"
        // report). Degrade to a plain message view instead — a relaunch picks up a clean
        // table. Literal text (no getString) so a stale string table can't re-throw here.
        return try {
            inflater.inflate(R.layout.fragment_projection, container, false)
        } catch (t: Throwable) {
            android.util.Log.e("ProjectionFragment",
                "layout inflate failed (stale resources after update?) — degrading", t)
            inflateFailed = true
            TextView(inflater.context).apply {
                text = inflater.context.getString(R.string.projection_temporarily_unavailable)
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Layout failed to inflate (degraded fallback view is showing) — do NO setup: the
        // real views don't exist and there's nothing to drive.
        if (inflateFailed) return
        // Defensive: this is a non-critical feature screen — no setup step here should ever
        // be allowed to crash the whole app. Any unexpected throw is logged + swallowed so
        // the screen degrades (blank/idle) instead of taking the process down.
        try {
            alive = true
            spinnerApps = view.findViewById(R.id.projectionAppSpinner)
            btnCast = view.findViewById(R.id.projectionCastButton)
            btnStop = view.findViewById(R.id.projectionStopButton)
            btnAdjust = view.findViewById(R.id.projectionAdjustButton)
            statusText = view.findViewById(R.id.projectionStatus)
            statusDot = view.findViewById(R.id.projectionStatusDot)
            appToast = AppToast(view)
            stage = view.findViewById(R.id.projectionStage)
            bounds = view.findViewById(R.id.projectionBounds)
            progress = view.findViewById(R.id.projectionProgress)
            scaleGroup = view.findViewById(R.id.projectionScaleGroup)
            presetGroup = view.findViewById(R.id.projectionPresetGroup)
            aspectLockSwitch = view.findViewById(R.id.projectionAspectLockSwitch)
            autoStartSwitch = view.findViewById(R.id.projectionAutoStartSwitch)
            speedOverlaySwitch.bind(view.findViewById(R.id.projectionSpeedOverlaySwitch))
            adasHideSwitch.bind(view.findViewById(R.id.projectionAdasHideSwitch))
            transportGroup = view.findViewById(R.id.projectionTransportGroup)
            transportStatus = view.findViewById(R.id.projectionTransportStatus)
            projectionPathGroup = view.findViewById(R.id.projectionPathGroup)
            projectionTargetGroup = view.findViewById(R.id.projectionTargetGroup)
            projectionPathStatus = view.findViewById(R.id.projectionPathStatus)
            projectionGeometryControls = view.findViewById(R.id.projectionGeometryControls)
            diLink5ProjectionControls = view.findViewById(R.id.projectionDiLink5Controls)
            texture = view.findViewById(R.id.projectionTexture)
            btnFullscreen = view.findViewById(R.id.projectionFullscreenButton)
            scroll = view.findViewById(R.id.projectionScroll)
            content = view.findViewById(R.id.projectionContent)
            stageCard = view.findViewById(R.id.projectionMirrorCard)
            dock = view.findViewById(R.id.projectionDock)
            settings = view.findViewById(R.id.projectionSettings)
            hud = view.findViewById(R.id.projectionHud)
            emptyState = view.findViewById(R.id.projectionEmptyState)
            // The fragment instance outlives its view; this state must match the fresh views.
            theater = false
            fullscreenButtonTheater = false
            busy = false
            castInFlight = false
            statusDotRes = R.drawable.status_dot_neutral
            content?.let {
                contentPadding = intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)
            }

            btnCast?.setOnClickListener { onCastClicked() }
            btnStop?.setOnClickListener { onStopClicked() }
            btnAdjust?.setOnClickListener { toggleAdjust() }
            btnFullscreen?.setOnClickListener { setTheater(!theater) }
            theaterBackCallback.isEnabled = false
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, theaterBackCallback)
            wireAppSpinner()
            wireBounds()
            wireClusterInputKeys()
            wireTexture()
            wireStageLayout()
            wireScaleGroup()
            wirePresetGroup()
            wireAspectLockSwitch()
            wireAutoStartSwitch()
            wireTransportGroup()
            wireProjectionPathGroups()
            wireProjectionGeometryControls(view)
            com.overdrive.app.ui.view.ProjectionAppTools.attach(this, view) { selectedPackage() }
            restoreScaleMode()
            clipMirrorToCard()
            updateButtons()
            // Per-app window restore happens in loadApps() once the spinner selection settles
            // (the app list loads async in onResume, so there's no selected package yet here).
            // Seed the stage to the default panel aspect (8:3) as soon as the page is measured;
            // the status poll refines it to the real panel aspect once known.
            stage?.post { if (alive) updateStageAspect(panelAspect()) }
        } catch (t: Throwable) {
            android.util.Log.e("ProjectionFragment", "onViewCreated failed", t)
        }
    }

    override fun onResume() {
        super.onResume()
        if (inflateFailed) return
        mirrorRequested = true
        val requestGeneration = ++mirrorRequestGeneration
        registerMirrorBinderReceiver()
        btnAdjust?.setText(R.string.projection_adjust_bounds)
        // Resolve cast state and panel geometry before app/config refreshes. On a cold entry
        // those slower requests must not delay sizing and attaching the already-live mirror.
        submitIo {
            val status = fetchStatus() ?: return@submitIo
            postMain {
                if (!isMirrorRequestCurrent(requestGeneration)) return@postMain
                applyStatus(status)
            }
        }
        startStatusPolling(requestGeneration)
        // Refresh the picker so an app uninstalled since we last loaded drops out of the
        // list (a long-lived fragment would otherwise keep offering a since-removed app).
        loadApps()
        refreshSavedSwitches()
    }

    override fun onPause() {
        super.onPause()
        if (inflateFailed) return
        mirrorRequested = false
        mirrorRequestGeneration++
        cancelPendingMirrorRestart()
        stopStatusPolling()
        unregisterMirrorBinderReceiver()
        // Never leave the activity without its rail and app bar once this screen is not in front.
        setTheater(false)
        // Clear the live-drag gate: any in-flight drag POST is now irrelevant, and if its
        // completion never ran (executor shut down / request abandoned) a stuck flag would
        // suppress live tracking for the rest of this fragment's life.
        resizeInFlight.set(false)
        // Leave adjust mode + persist the current window so it returns as the user left it.
        bounds?.let { b ->
            val w = b.currentWindow()
            persistWindow(w.left, w.top, w.right, w.bottom)
        }
        adjusting = false
        btnAdjust?.setText(R.string.projection_adjust_bounds)
        // Tear the mirror down while paused so the daemon isn't compositing into a surface
        // we're about to release; the cast itself stays up (HTTP), the mirror re-arms on resume.
        detachMirror()
        applyBoundsMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (inflateFailed || !alive) return
        // MainActivity handles orientation/screen-size changes without recreation. Stop the
        // mirror before that retained view tree is measured at its new size (the proven
        // stop → resize → restart ordering for a live mirror on this firmware).
        prepareForSurfaceResize()
        stage?.post {
            if (!alive) return@post
            updateStageAspect(panelAspect())
            scheduleMirrorRestart()
        }
    }

    override fun onDestroyView() {
        alive = false
        mirrorRequested = false
        mirrorRequestGeneration++
        cancelPendingMirrorRestart()
        stopStatusPolling()
        main.removeCallbacksAndMessages(null)
        // Detach then release our Surface wrapper without ever handing the daemon a freed
        // buffer; on a slow (unconfirmed) detach the release is deferred onto mirrorIo, ordered
        // after the detach. Safe past onDestroyView — it only touches mirrorSurface (under
        // surfaceLock), never a view ref. (The TextureView owns its SurfaceTexture's lifecycle.)
        detachAndReleaseMirrorSurface()
        stageLayoutListener?.let { listener ->
            scroll?.removeOnLayoutChangeListener(listener)
            dock?.removeOnLayoutChangeListener(listener)
        }
        stageLayoutListener = null
        if (theater) {
            theater = false
            (activity as? MainActivity)?.setContentImmersive(false)
        }
        spinnerApps = null; btnCast = null; btnStop = null; btnAdjust = null
        appToast?.cancel()
        appToast = null
        lastStickyError = null
        statusText = null; statusDot = null; stage = null; bounds = null; progress = null
        scaleGroup = null; presetGroup = null; aspectLockSwitch = null
        autoStartSwitch = null; transportGroup = null; transportStatus = null
        projectionPathGroup = null; projectionTargetGroup = null; projectionPathStatus = null
        projectionGeometryControls = null; diLink5ProjectionControls = null
        projectionSliders.clear(); projectionSliderValues.clear()
        speedOverlaySwitch.unbind(); adasHideSwitch.unbind()
        texture = null; btnFullscreen = null; scroll = null; content = null; stageCard = null
        dock = null; settings = null; hud = null; emptyState = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        io.shutdown()
        mirrorIo.shutdown()
        clusterInputIo.shutdownNow()
        super.onDestroy()
    }

    /** Persist the user's app pick as they make it, so loadApps' adapter rebuild (every
     *  resume) can restore it instead of snapping back to the list's first entry.
     *
     *  Spinner selection callbacks also fire for PROGRAMMATIC changes (new adapter /
     *  setSelection) — asynchronously, on the next layout pass — so a suppress-flag around
     *  the call sites (the pattern the other controls use) can't cover them. Instead
     *  loadApps records the position it programmatically settles the spinner at
     *  ([pendingSpinnerEcho]) and the listener swallows callbacks until that echo lands.
     *  Without this, a resume where the daemon transiently omits the saved app would fire
     *  onItemSelected(0) and clobber the user's persisted pick with the list's first entry. */
    private fun wireAppSpinner() {
        spinnerApps?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val expected = pendingSpinnerEcho
                if (expected != -1) {
                    if (pos == expected) pendingSpinnerEcho = -1
                    return   // programmatic echo, not a user pick
                }
                if (pos >= 0 && pos < appPackages.size) {
                    val pkg = appPackages[pos]
                    persistSpinnerSelection(pkg)
                    // While a cast is LIVE the box belongs to the cast app, not to this pick.
                    // Restoring here would push the newly-selected app's remembered rect onto the
                    // app currently on the cluster (and later persist the drag under the wrong
                    // key). The box rebinds when the cast target actually changes (applyStatus).
                    if (casting && castPackage.isNotEmpty()) return
                    // A REAL user pick: restore this app's own saved window (or FULL default) so the
                    // box always matches the selected app — otherwise it would keep showing the
                    // previous app's box and a later persist/onPause would write it under this
                    // app's key (the audited cross-key bug).
                    restoreWindowFor(pkg)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { /* keep last pick */ }
        }
    }

    // ── Bounding-rect wiring ─────────────────────────────────────────────────────────

    private fun wireBounds() {
        val b = bounds ?: return
        // The box is expressed in CLUSTER space, so it is always panel-shaped by construction
        // (never the square the old view-px + separately-resolved-aspect model produced) and needs
        // no conversion before being handed to the daemon as freeform window bounds.
        b.onWindowChanged = window@{ l, t, r, bot, commit ->
            if (layoutActive || layoutBusy) return@window
            // The box is the cast app's FREEFORM BOUNDS on the cluster — NOT a head-unit preview
            // sub-rect. The mirror always shows the whole cluster and does not move with the box,
            // so a box change never re-casts the mirror.
            //
            // Intermediate (commit=false) frames are forwarded too; the daemon tracks them live only
            // where the placement allows it (freeform) and otherwise applies on release. They are
            // DROPPED when one is already in flight (see [resizeInFlight]) so a slow daemon can't
            // build a backlog of stale rects on the single HTTP executor. The commit frame is never
            // dropped — it is the authoritative, verified apply.
            if (commit) {
                persistWindow(l, t, r, bot)
                if (casting) submitCommit(l, t, r, bot)
            } else if (casting && resizeInFlight.compareAndSet(false, true)) {
                val pkg = geometryPackage() ?: ""
                val sessionGeneration = castSessionGeneration
                // Release the gate if the task never actually got queued (executor shut down or
                // rejected): the `finally` below only runs for a block that RAN, so without this
                // the flag would stick true and silently disable live tracking from then on.
                if (!submitIo {
                        try { postClusterResize(l, t, r, bot, commit = false, pkg = pkg,
                            sessionGeneration = sessionGeneration) }
                        finally { resizeInFlight.set(false) }
                    }) {
                    resizeInFlight.set(false)
                }
            }
        }
        // CONTROL taps/swipes are normalized to the WHOLE VIEW (= whole cluster) now, since the
        // mirror shows the full cluster. The relay maps full-surface px → full-cluster px through
        // the daemon's published projection (ClusterInputRelay → destinationToSource).
        b.onTap = { nx, ny ->
            val point = boxNormToView(nx, ny)
            val generation = if (layoutActive) layoutGeneration else 0L
            val epoch = ClusterViewMirrorClient.currentInputEpoch()
            if (point != null) submitIo { postTap(point, generation, epoch) }
        }
        b.onSwipe = { nx1, ny1, nx2, ny2, ms ->
            val start = boxNormToView(nx1, ny1)
            val end = boxNormToView(nx2, ny2)
            val generation = if (layoutActive) layoutGeneration else 0L
            val epoch = ClusterViewMirrorClient.currentInputEpoch()
            if (start != null && end != null) submitIo { postSwipe(start, end, ms, generation, epoch) }
        }
        b.onTouchFrame = stream@{ event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN
                && !ClusterViewMirrorClient.supportsTouchStream(mirrorSessionId)) return@stream false
            val rect = lastPaneRect
            val accepted = ClusterViewMirrorClient.touch(
                mirrorSessionId, event, b.width, b.height,
                rect[0], rect[1], rect[2], rect[3]
            )
            // Capability was established before DOWN. A rejected press (letterbox,
            // teardown race) remains claimed so it cannot be replayed through HTTP.
            accepted || event.actionMasked == MotionEvent.ACTION_DOWN
        }
        b.onTouchCancel = { ClusterViewMirrorClient.cancelTouch(mirrorSessionId) }
    }

    /** A compact menu fits both portrait and landscape docks and leaves the mirror fully
     *  available for gestures. Every key follows the same live session/display as touch. */
    private fun wireClusterInputKeys() {
        val row = spinnerApps?.parent as? LinearLayout ?: return
        val button = MaterialButton(requireContext()).apply {
            text = "⋮"
            contentDescription = getString(R.string.projection_cluster_controls)
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            val size = resources.getDimensionPixelSize(R.dimen.dashboard_modern_touch_target)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = (8 * resources.displayMetrics.density).roundToInt()
            }
            setOnClickListener {
                val labels = arrayOf(
                    getString(R.string.projection_cluster_back),
                    getString(R.string.projection_cluster_home),
                    getString(R.string.projection_cluster_volume_down),
                    getString(R.string.projection_cluster_volume_up)
                )
                val keys = intArrayOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
                    KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.projection_cluster_controls)
                    .setItems(labels) { _, index ->
                        val eventTime = android.os.SystemClock.uptimeMillis()
                        val inputEpoch = ClusterViewMirrorClient.currentInputEpoch()
                        submitClusterInput {
                            val accepted = ClusterViewMirrorClient.key(
                                mirrorSessionId, keys[index], eventTime, inputEpoch)
                            if (!accepted) postMain {
                                notifyToast(getString(R.string.projection_cluster_control_unavailable),
                                    AppToast.Kind.WARNING)
                            }
                        }
                    }.show()
            }
        }
        row.addView(button)
    }

    /**
     * Re-cast the mirror at the current box (a box move/resize/preset commit or a scale-mode
     * change). On this firmware the mirror geometry can only be changed by a full STOP + re-cast
     * onto a FRESH Surface + fresh display token — a live re-projection onto a still-bound token is
     * silently ignored. We reuse the exact detach→recreate-Surface→attach path the view-resize uses:
     * [prepareForSurfaceResize] tears the daemon mirror down (bumping the surface generation so any
     * in-flight attach no-ops) and [scheduleMirrorRestart] recreates the Surface at the settled size
     * and re-attaches at the current box. FIFO ordering on mirrorIo guarantees detach-before-attach.
     */
    private fun restartMirrorForResize() {
        if (!alive || !mirrorRequested || !casting) return
        prepareForSurfaceResize()
        scheduleMirrorRestart()
    }

    /** The full mirror surface rect (whole TextureView), or null if not laid out yet.
     *
     *  The mirror ALWAYS shows the WHOLE cluster now (decoupled from the resize box): it's a
     *  see-and-touch view of the cluster, not a preview of a sub-rect. The box separately drives
     *  the cast app's freeform bounds ON the cluster (via cluster-resize), so the mirror shows the
     *  resized app plus whatever surrounds it. Input taps normalize to this full surface and the
     *  relay maps them full-surface → full-cluster px. */
    private fun currentBoxSurfaceRect(): IntArray? {
        val tv = texture ?: return null
        if (tv.width <= 0 || tv.height <= 0) return null
        return intArrayOf(0, 0, tv.width, tv.height)
    }

    // ── Mirror TextureView (the live cluster renders into this view's Surface) ────────

    private fun wireTexture() {
        val tv = texture ?: return
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                replaceMirrorSurface(st, w, h)
                if (mirrorRequested && casting) armMirror()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                prepareForSurfaceResize()
                // prepareForSurfaceResize already detached (nothing composites now) and bumped
                // the generation. Only RESIZE the buffer here — the existing Surface wrapper
                // stays valid across a size change, and the scheduled restart recreates it at
                // the settled size and re-attaches. Allocating a fresh Surface here would just
                // be released by that restart without ever reaching the daemon.
                pinBuffer(st, w, h)
                scheduleMirrorRestart()
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                cancelPendingMirrorRestart()
                // Returning true lets the framework recycle st immediately — only safe once the
                // daemon has CONFIRMED it unbound its output. If the detach isn't confirmed, keep
                // ownership of st (return false) and release it (and our Surface) only after the
                // detach lands on mirrorIo, so the daemon never composites into a freed buffer.
                return detachAndReleaseMirrorSurface(alsoRelease = {
                    try { st.release() } catch (_: Throwable) {}
                })
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) { /* no-op */ }
        }
        // If the SurfaceTexture is already available (view reused across re-entry), the
        // listener won't fire again — create the Surface now so a live cast can re-arm.
        if (tv.isAvailable) {
            val st = tv.surfaceTexture
            if (st != null && tv.width > 0 && tv.height > 0) {
                replaceMirrorSurface(st, tv.width, tv.height)
            }
        }
    }

    /** Re-fit the stage whenever the viewport or the dock under it changes size (rotation,
     * split-screen, rail/inset relayout, full screen). Posted out of the current layout pass
     * so TextureView receives one settled resize. */
    private fun wireStageLayout() {
        val listener = View.OnLayoutChangeListener {
                v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val resized = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
            if (resized && right > left) {
                v.post { if (alive) updateStageAspect(panelAspect()) }
            }
        }
        stageLayoutListener = listener
        scroll?.addOnLayoutChangeListener(listener)
        dock?.addOnLayoutChangeListener(listener)
    }

    private fun replaceMirrorSurface(st: SurfaceTexture, w: Int, h: Int) {
        pinBuffer(st, w, h)
        surfaceGeneration++
        synchronized(surfaceLock) {
            mirrorSurface?.let { try { it.release() } catch (_: Throwable) {} }
            mirrorSurface = Surface(st)
        }
    }

    /** Size the SurfaceTexture buffer to the VIEW (so the daemon's letterbox projection lands
     *  1:1 with no TextureView auto-stretch). */
    private fun pinBuffer(st: SurfaceTexture, w: Int, h: Int) {
        st.setDefaultBufferSize(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    /** Arm the mirror: hand the Surface to the daemon and project the WHOLE cluster (letterboxed
     *  per the active scale mode) into the full mirror surface — a see-and-touch view of the whole
     *  cluster, independent of the resize box (which drives the cast app's freeform bounds instead).
     *  Defers a frame if the view isn't measured yet. */
    private fun armMirror() {
        if (!alive || !mirrorRequested || !casting || surfaceRestart != null) return
        val tv = texture ?: return
        val hasSurface = synchronized(surfaceLock) { mirrorSurface?.isValid == true }
        if (!hasSurface) return
        // Mirror the WHOLE cluster into the full surface (the box no longer crops the mirror).
        val r = currentBoxSurfaceRect()
        if (r == null) {
            tv.post { if (alive && mirrorRequested && casting) armMirror() }
            return
        }
        lastPaneRect = r
        val mode = scaleMode
        val generation = surfaceGeneration
        if (!mirrorAttachRunning.compareAndSet(false, true)) {
            mirrorAttachAgain.set(true)
            return
        }
        submitMirror {
            try {
                if (!alive || !mirrorRequested || !casting
                    || generation != surfaceGeneration) return@submitMirror
                val state = synchronized(surfaceLock) {
                    val surface = mirrorSurface
                    if (surface != null && surface.isValid && generation == surfaceGeneration) {
                        ClusterViewMirrorClient.attach(
                            mirrorSessionId, surface, r[0], r[1], r[2], r[3], mode
                        )
                    } else {
                        MODE_STOPPED
                    }
                }
                // A dispatched publish request re-arms from the broadcast; if none arrives the
                // next (throttled) poll renders the service-down error instead.
                val publishPending = state == MODE_SERVICE_DOWN && requestMirrorPublish()
                if (!publishPending && mirrorRequested && generation == surfaceGeneration) {
                    mirrorMode = state
                    postMain {
                        if (mirrorRequested && generation == surfaceGeneration) {
                            renderMirrorState(state)
                        }
                    }
                }
            } finally {
                mirrorAttachRunning.set(false)
                if (mirrorAttachAgain.getAndSet(false)) {
                    postMain { if (mirrorRequested && casting) armMirror() }
                }
            }
        }
    }

    /** Ask the daemon to re-broadcast its mirror Binder (throttled) on the HTTP executor, so
     *  [mirrorIo] never waits on the request. @return true when a request was dispatched now. */
    private fun requestMirrorPublish(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastMirrorPublishRequestMs < MIRROR_PUBLISH_RETRY_MS) return false
        lastMirrorPublishRequestMs = now
        return submitIo { httpPostSuccess("/api/vehicle/cluster-mirror-publish", JSONObject()) }
    }

    private fun registerMirrorBinderReceiver() {
        if (mirrorBinderReceiverRegistered) return
        try {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(
                mirrorBinderReceiver,
                android.content.IntentFilter(ClusterViewMirrorClient.ACTION_PUBLISH),
                android.Manifest.permission.DUMP,
                null
            )
            mirrorBinderReceiverRegistered = true
        } catch (t: Throwable) {
            android.util.Log.w("ProjectionFragment", "mirror binder receiver register failed", t)
        }
    }

    private fun unregisterMirrorBinderReceiver() {
        if (!mirrorBinderReceiverRegistered) return
        mirrorBinderReceiverRegistered = false
        try {
            requireContext().unregisterReceiver(mirrorBinderReceiver)
        } catch (_: Throwable) {}
    }

    /**
     * Tell the daemon to unbind its mirror output. Bumps [surfaceGeneration] first so any
     * in-flight attach/reproject on [mirrorIo] no-ops before the detach runs (they re-check
     * the generation under [surfaceLock]).
     *
     * @param wait when true, block the caller until the daemon confirms the detach (or the
     *   timeout elapses) and return whether it CONFIRMED. Use this ONLY where the caller is
     *   about to release the backing SurfaceTexture and must not let the daemon composite into
     *   freed memory — [onSurfaceTextureDestroyed]. The resize path must NOT wait: it hands off
     *   to a delayed restart and the single-thread [mirrorIo] already orders detach-before-
     *   reattach, so blocking the main thread there is pure jank (up to [MIRROR_DETACH_TIMEOUT_MS]).
     * @return true iff the detach was confirmed by the daemon within the timeout. Always true
     *   for the non-waiting (fire-and-forget) path — the ordering guarantee is the contract there.
     */
    private fun detachMirror(wait: Boolean = false): Boolean {
        mirrorAttachAgain.set(false)
        surfaceGeneration++
        mirrorMode = MODE_STOPPED
        val task = Runnable { ClusterViewMirrorClient.detach(mirrorSessionId) }
        if (!wait) {
            submitMirror { task.run() }
            return true
        }
        return try {
            mirrorIo.submit(task).get(MIRROR_DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            true
        } catch (t: Throwable) {
            // Timed out / rejected / interrupted: the daemon may still be compositing into the
            // output. The caller MUST NOT release the SurfaceTexture yet (see onSurfaceTextureDestroyed).
            android.util.Log.w("ProjectionFragment", "mirror detach not confirmed", t)
            false
        }
    }

    /** Prepare for a view/surface resize: cancel a pending restart and drop the daemon mirror
     *  so it isn't compositing into a surface that is about to change size. Fire-and-forget —
     *  the delayed restart re-attaches, and [mirrorIo]'s FIFO ordering guarantees this detach
     *  runs before that re-attach without blocking the main thread. */
    private fun prepareForSurfaceResize() {
        cancelPendingMirrorRestart()
        if (mirrorRequested && casting) detachMirror()
    }

    /**
     * Detach the daemon mirror, then release our [mirrorSurface] wrapper (and any [alsoRelease]
     * such as the backing SurfaceTexture) WITHOUT ever handing the daemon a freed buffer.
     *
     * If the daemon confirms the detach within the timeout, release inline and return true. If
     * it does NOT confirm, the daemon may still be compositing, so defer the release onto
     * [mirrorIo] — FIFO-ordered strictly after the pending detach task, off the main thread —
     * and return false. When the executor is already shut down no detach can be pending, so the
     * release runs inline as a last resort rather than leaking the surface.
     */
    private fun detachAndReleaseMirrorSurface(alsoRelease: (() -> Unit)? = null): Boolean {
        val confirmed = detachMirror(wait = true)
        val release = Runnable {
            synchronized(surfaceLock) {
                mirrorSurface?.let { try { it.release() } catch (_: Throwable) {} }
                mirrorSurface = null
            }
            alsoRelease?.let { try { it() } catch (_: Throwable) {} }
        }
        if (confirmed) { release.run(); return true }
        val queued = try {
            if (!mirrorIo.isShutdown) { mirrorIo.execute(release); true } else false
        } catch (_: RejectedExecutionException) { false }
        if (!queued) release.run()
        return false
    }

    /** Recreate the Surface only after resize/layout callbacks settle, then attach. This is
     * the proven recovery recipe for stale/black previews after a size change. */
    private fun scheduleMirrorRestart() {
        if (!alive || !mirrorRequested || !casting) return
        cancelPendingMirrorRestart()
        lateinit var restart: Runnable
        restart = Runnable {
            if (surfaceRestart !== restart) return@Runnable
            surfaceRestart = null
            val tv = texture ?: return@Runnable
            val st = tv.surfaceTexture ?: return@Runnable
            if (!tv.isAvailable || tv.width <= 0 || tv.height <= 0) return@Runnable
            replaceMirrorSurface(st, tv.width, tv.height)
            armMirror()
        }
        surfaceRestart = restart
        main.postDelayed(restart, MIRROR_RESTART_DELAY_MS)
    }

    private fun cancelPendingMirrorRestart() {
        surfaceRestart?.let { main.removeCallbacks(it) }
        surfaceRestart = null
    }

    // ── Scaling mode (Fit / Fill / Zoom) ─────────────────────────────────────────────

    private fun wireScaleGroup() {
        val g = scaleGroup ?: return
        g.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressScaleListener) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.projectionScaleFill -> SCALE_FILL
                R.id.projectionScaleZoom -> SCALE_ZOOM
                else -> SCALE_FIT
            }
            if (mode == scaleMode) return@addOnButtonCheckedListener
            scaleMode = mode
            persistScaleMode(mode)
            // Scale mode governs how the full-cluster mirror maps into the (panel-shaped) head-unit
            // stage. Like a box move/resize, this can only be applied by a full stop + re-cast on
            // this firmware — a live re-projection is silently ignored — so re-arm the mirror at the
            // new mode. When no mirror is live yet, arm it fresh.
            if (mirrorRequested && casting) {
                if (mirrorMode == MODE_ACTIVE) restartMirrorForResize() else armMirror()
            }
        }
    }

    /** Reflect [scaleMode] into the toggle group without firing the listener. Called on restore
     *  and after loading the persisted mode. */
    private fun applyScaleModeToUi(mode: Int) {
        val g = scaleGroup ?: return
        val id = when (mode) {
            SCALE_FILL -> R.id.projectionScaleFill
            SCALE_ZOOM -> R.id.projectionScaleZoom
            else -> R.id.projectionScaleFit
        }
        suppressScaleListener = true
        try { g.check(id) } finally { suppressScaleListener = false }
    }

    private fun restoreScaleMode() {
        val sp = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scaleMode = sp?.getInt(K_MODE, SCALE_FIT) ?: SCALE_FIT
        applyScaleModeToUi(scaleMode)
    }

    private fun persistScaleMode(mode: Int) {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putInt(K_MODE, mode)?.apply()
    }

    // ── Cluster window presets + aspect lock ─────────────────────────────────────────

    private fun wirePresetGroup() {
        val g = presetGroup ?: return
        g.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressPresetListener) return@addOnButtonCheckedListener
            val which = when (checkedId) {
                R.id.projectionPresetLeft -> ProjectionBoundsGeometry.PRESET_LEFT
                R.id.projectionPresetRight -> ProjectionBoundsGeometry.PRESET_RIGHT
                R.id.projectionPresetCenter -> ProjectionBoundsGeometry.PRESET_CENTER
                else -> ProjectionBoundsGeometry.PRESET_FULL
            }
            // applyPreset commits the new preview rect (fires onWindowChanged → reproject + persist).
            bounds?.applyPreset(which)
        }
    }

    private fun wireAspectLockSwitch() {
        val sw = aspectLockSwitch ?: return
        val locked = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(K_ASPECT_LOCK, false) ?: false
        suppressAspectListener = true
        try { sw.isChecked = locked } finally { suppressAspectListener = false }
        bounds?.setAspectLocked(locked, notifyChange = false)
        sw.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAspectListener) return@setOnCheckedChangeListener
            bounds?.setAspectLocked(isChecked)   // refits + commits when turning on
            context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putBoolean(K_ASPECT_LOCK, isChecked)?.apply()
        }
    }

    // ── Auto-start on ACC-on (mutually exclusive with RoadSense map auto-project) ─────

    private fun wireAutoStartSwitch() {
        val sw = autoStartSwitch ?: return
        sw.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoStartListener) return@setOnCheckedChangeListener
            if (isChecked) onAutoStartEnabled() else onAutoStartDisabled()
        }
    }

    // ── Cluster transport (projection.clusterTransport: auto/legacy/dilink5) ──

    private fun wireTransportGroup() {
        val g = transportGroup ?: return
        g.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressTransportListener) return@addOnButtonCheckedListener
            val value = when (checkedId) {
                R.id.projectionTransportLegacy -> "legacy"
                R.id.projectionTransportDilink5 -> "dilink5"
                else -> "auto"
            }
            if (value == configuredTransport) return@addOnButtonCheckedListener
            onTransportSelected(value)
        }
    }

    private fun setTransportChecked(value: String) {
        val g = transportGroup ?: return
        val id = when (value) {
            "legacy" -> R.id.projectionTransportLegacy
            "dilink5" -> R.id.projectionTransportDilink5
            else -> R.id.projectionTransportAuto
        }
        if (g.checkedButtonId == id) return
        suppressTransportListener = true
        try { g.check(id) } finally { suppressTransportListener = false }
    }

    private fun setTransportStatusLine(resolved: String, source: String) {
        if (resolved.isEmpty()) return
        transportStatus?.text =
            getString(R.string.projection_transport_active, resolved, source)
    }

    /**
     * Persist the transport choice. The daemon applies it live only while the
     * cluster is idle; otherwise the saved value takes effect on the next
     * camera-service restart (surfaced via a toast, mirroring the camera-mode
     * selector's contract). On any failure the control reverts to the last
     * known-persisted value so the UI never lies about what is saved.
     */
    private fun onTransportSelected(value: String) {
        val previous = configuredTransport
        configuredTransport = value
        transportWriteInFlight = true
        submitIo {
            var restartRequired = false
            var resolved = ""
            var source = ""
            val ok = try {
                val body = httpPostForBody(
                    "/api/vehicle/cluster-transport",
                    JSONObject().put("transport", value)
                )
                val j = if (body != null) JSONObject(body) else null
                if (j != null && j.optBoolean("success", false)) {
                    restartRequired = j.optBoolean("restartRequired", false)
                    resolved = j.optString("clusterTransport", "")
                    source = j.optString("clusterTransportSource", "")
                    true
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
            postMain {
                transportWriteInFlight = false
                if (!alive) return@postMain
                if (!ok) {
                    configuredTransport = previous
                    setTransportChecked(previous)
                    notifyToast(
                        getString(R.string.projection_transport_save_failed),
                        AppToast.Kind.ERROR
                    )
                    return@postMain
                }
                setTransportStatusLine(resolved, source)
                if (restartRequired) {
                    notifyToast(
                        getString(R.string.projection_transport_restart_required),
                        AppToast.Kind.WARNING
                    )
                }
            }
        }
    }

    private fun wireProjectionPathGroups() {
        projectionPathGroup?.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || suppressProjectionPathListener) return@addOnButtonCheckedListener
            val value = when (id) {
                R.id.projectionPathHosted -> "hosted"
                R.id.projectionPathDirect -> "direct"
                else -> "auto"
            }
            if (value != configuredProjectionPath) saveProjectionChoice(
                "di5ProjectionTransport", value, configuredProjectionPath
            ) {
                configuredProjectionPath = it
                setProjectionPathChecked(it)
                updateProjectionControlMode()
            }
        }
        projectionTargetGroup?.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || suppressProjectionPathListener) return@addOnButtonCheckedListener
            val value = if (id == R.id.projectionTargetFull) "full" else "standard"
            if (value != configuredProjectionTarget) saveProjectionChoice(
                "di5ProjectionTarget", value, configuredProjectionTarget
            ) { configuredProjectionTarget = it; setProjectionTargetChecked(it) }
        }
    }

    private data class ProjectionSliderSpec(
        val key: String,
        val sliderId: Int,
        val valueId: Int,
        val labelId: Int,
        val minimum: Int
    )

    private fun wireProjectionGeometryControls(root: View) {
        PROJECTION_SLIDERS.forEach { spec ->
            val slider = root.findViewById<SeekBar>(spec.sliderId)
            val value = root.findViewById<TextView>(spec.valueId)
            projectionSliders[spec.key] = slider
            projectionSliderValues[spec.key] = value
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = getString(
                        R.string.projection_scale_value,
                        getString(spec.labelId),
                        spec.minimum + progress
                    )
                }
                override fun onStartTrackingTouch(view: SeekBar?) = Unit
                override fun onStopTrackingTouch(view: SeekBar?) {
                    val selected = spec.minimum + (view?.progress ?: 0)
                    submitIo {
                        val saved = postUnified("projection", JSONObject().put(spec.key, selected))
                        if (!saved) postMain {
                            if (alive) notifyToast(
                                getString(R.string.projection_setting_save_failed),
                                AppToast.Kind.ERROR
                            )
                        } else if (casting) postMain {
                            if (alive) notifyToast(
                                getString(R.string.projection_transport_restart_required),
                                AppToast.Kind.WARNING
                            )
                        }
                    }
                }
            })
        }
        applyProjectionSliderValues(PROJECTION_SLIDER_DEFAULTS)
        updateProjectionControlMode()
    }

    private fun applyProjectionSliderValues(values: IntArray) {
        PROJECTION_SLIDERS.forEachIndexed { index, spec ->
            val selected = values.getOrElse(index) { PROJECTION_SLIDER_DEFAULTS[index] }
                .coerceIn(spec.minimum, spec.minimum + (projectionSliders[spec.key]?.max ?: 0))
            projectionSliders[spec.key]?.progress = selected - spec.minimum
            projectionSliderValues[spec.key]?.text = getString(
                R.string.projection_scale_value, getString(spec.labelId), selected
            )
        }
    }

    private fun updateProjectionControlMode() {
        val diLink5 = projectionUsesDiLink5Controls(resolvedProjectionTransport)
        diLink5ProjectionControls?.visibility = if (diLink5) View.VISIBLE else View.GONE
        val hostedEnabled = configuredProjectionPath != "direct"
        val directEnabled = configuredProjectionPath != "hosted"
        PROJECTION_SLIDERS.forEach { spec ->
            projectionSliders[spec.key]?.isEnabled =
                diLink5 && if (spec.key == "di5DirectScalePercent") directEnabled else hostedEnabled
        }
        projectionGeometryControls?.visibility = View.VISIBLE
    }

    private fun saveProjectionChoice(
        key: String,
        value: String,
        previous: String,
        apply: (String) -> Unit
    ) {
        apply(value)
        submitIo {
            val ok = postUnified("projection", JSONObject().put(key, value))
            if (!ok) postMain { if (alive) apply(previous) }
        }
    }

    private fun setProjectionPathChecked(value: String) {
        val id = when (value) {
            "hosted" -> R.id.projectionPathHosted
            "direct" -> R.id.projectionPathDirect
            else -> R.id.projectionPathAuto
        }
        suppressProjectionPathListener = true
        try { projectionPathGroup?.check(id) } finally { suppressProjectionPathListener = false }
    }

    private fun setProjectionTargetChecked(value: String) {
        val id = if (value == "full") R.id.projectionTargetFull
        else R.id.projectionTargetStandard
        suppressProjectionPathListener = true
        try { projectionTargetGroup?.check(id) } finally { suppressProjectionPathListener = false }
    }

    private fun setAutoStartChecked(on: Boolean) {
        val sw = autoStartSwitch ?: return
        suppressAutoStartListener = true
        try { sw.isChecked = on } finally { suppressAutoStartListener = false }
    }

    private fun onAutoStartEnabled() {
        // Need a package to auto-cast. Prefer the current spinner selection; if none, refuse.
        val idx = spinnerApps?.selectedItemPosition ?: -1
        if (idx < 0 || idx >= appPackages.size) {
            setAutoStartChecked(false)
            notifyToast(getString(R.string.projection_autostart_need_app), AppToast.Kind.WARNING)
            return
        }
        val pkg = appPackages[idx]
        // Mutual exclusion: if RoadSense map auto-project is on, confirm handing over the
        // cluster (it can only show one thing on power-up).
        if (roadSenseAutoProject) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.projection_autostart_conflict_title)
                .setMessage(R.string.projection_autostart_conflict_body)
                .setNegativeButton(R.string.action_cancel) { _, _ -> setAutoStartChecked(false) }
                .setPositiveButton(R.string.projection_autostart_conflict_confirm) { _, _ ->
                    // Clear the sibling FIRST so there is never a both-on window, then enable us.
                    submitIo {
                        val a = postUnified("navMap", JSONObject().put("autoProjectCluster", false))
                        if (a) roadSenseAutoProject = false
                        val b = postUnified("projection", JSONObject()
                            .put("autoStartOnAcc", true).put("autoStartPackage", pkg))
                        // If the write didn't land, revert the switch so the UI matches the server.
                        if (!(a && b)) postMain { setAutoStartChecked(false) }
                    }
                }
                .setOnCancelListener { setAutoStartChecked(false) }
                .show()
            return
        }
        submitIo {
            val ok = postUnified("projection", JSONObject()
                .put("autoStartOnAcc", true).put("autoStartPackage", pkg))
            if (!ok) postMain { setAutoStartChecked(false) }
        }
    }

    private fun onAutoStartDisabled() {
        submitIo {
            val ok = postUnified("projection", JSONObject().put("autoStartOnAcc", false))
            // Revert to ON if the write failed, so the switch reflects the server.
            if (!ok) postMain { setAutoStartChecked(true) }
        }
    }

    /** Persist the last-cast package as the auto-start candidate (does NOT enable auto-start
     *  — only records which app it would cast if the toggle is on). */
    private fun persistAutoStartPackage(pkg: String) {
        postUnified("projection", JSONObject().put("autoStartPackage", pkg))
    }

    /** Read both auto-start flags and the cluster overlay flags from the unified config so the
     *  switches reflect the saved state and the conflict dialog knows the sibling's value. */
    private fun refreshSavedSwitches() {
        val overlaySwitches = listOf(speedOverlaySwitch, adasHideSwitch)
        val seqs = overlaySwitches.map { it.writeSeq }
        submitIo {
            var projOn = false
            var mapOn = false
            var surveillance: JSONObject? = null
            try {
                val body = httpGet("/api/settings/unified")
                if (body != null) {
                    val cfg = JSONObject(body).optJSONObject("config") ?: JSONObject()
                    cfg.optJSONObject("projection")?.let { projOn = it.optBoolean("autoStartOnAcc", false) }
                    cfg.optJSONObject("navMap")?.let { mapOn = it.optBoolean("autoProjectCluster", false) }
                    surveillance = cfg.optJSONObject("surveillance") ?: JSONObject()
                }
            } catch (_: Throwable) {}
            roadSenseAutoProject = mapOn
            val on = projOn
            val saved = surveillance
            postMain {
                setAutoStartChecked(on)
                if (saved != null) overlaySwitches.forEachIndexed { i, s -> s.applySaved(saved, seqs[i]) }
            }
        }
    }

    /** Switch for one boolean `surveillance.*` key shared with RoadSense; the daemon applies it live. */
    private inner class SurveillanceSwitch(private val key: String) {
        private var view: MaterialSwitch? = null
        private var suppress = false
        // Main thread only. Bumped per user flip so an older config read or failed write can't repaint it.
        var writeSeq = 0
            private set

        fun bind(sw: MaterialSwitch?) {
            view = sw ?: return
            sw.setOnCheckedChangeListener { _, isChecked ->
                if (suppress) return@setOnCheckedChangeListener
                val seq = ++writeSeq
                val queued = submitIo {
                    val ok = postUnified("surveillance", JSONObject().put(key, isChecked))
                    if (!ok) postMain {
                        if (seq != writeSeq) return@postMain
                        setChecked(!isChecked)
                        notifyToast(getString(R.string.projection_setting_save_failed), AppToast.Kind.ERROR)
                    }
                }
                if (!queued) setChecked(!isChecked)
            }
        }

        fun unbind() { view = null }

        fun applySaved(surveillance: JSONObject, seqAtRead: Int) {
            if (seqAtRead == writeSeq) setChecked(surveillance.optBoolean(key, false))
        }

        private fun setChecked(on: Boolean) {
            val sw = view ?: return
            suppress = true
            try { sw.isChecked = on } finally { suppress = false }
        }
    }

    private fun postUnified(section: String, data: JSONObject): Boolean =
        httpPostSuccess("/api/settings/unified",
            JSONObject().put("section", section).put("data", data))

    private fun toggleAdjust() {
        if (layoutActive || layoutBusy) return
        adjusting = !adjusting
        applyBoundsMode()
        btnAdjust?.setText(
            if (adjusting) R.string.projection_done_adjusting else R.string.projection_adjust_bounds
        )
        if (adjusting) {
            setStatus(getString(R.string.projection_status_adjust))
        } else {
            // Leaving adjust: persist the final window rect (the live edits already pushed it to
            // the cluster). The mirror shows the whole cluster, so no re-projection is needed.
            bounds?.let { b ->
                val w = b.currentWindow()
                persistWindow(w.left, w.top, w.right, w.bottom)
            }
            renderMirrorState(mirrorMode)
        }
    }

    /** Box mode follows: ADJUST when the user is adjusting; CONTROL when a mirror is live;
     *  IDLE otherwise. */
    private fun applyBoundsMode() {
        val b = bounds ?: return
        b.mode = when {
            adjusting -> ProjectionBoundsView.Mode.ADJUST
            mirrorMode == MODE_ACTIVE -> ProjectionBoundsView.Mode.CONTROL
            else -> ProjectionBoundsView.Mode.IDLE
        }
    }

    // ── App list + cast ─────────────────────────────────────────────────────────────

    private fun loadApps() {
        setBusy(true)
        val pm = context?.packageManager
        val res = resources
        val iconPx = (APP_ICON_DP * res.displayMetrics.density).roundToInt()
        submitIo {
            val apps = fetchApps(pm, res, iconPx)
            postMain {
                appPackages.clear(); appPackages.addAll(apps.map { it.pkg })
                val ctx = context ?: return@postMain
                // An empty list still names the field, without adding a selectable package.
                val rows = apps.ifEmpty {
                    listOf(AppEntry(getString(R.string.projection_pick_app), "", null))
                }
                spinnerApps?.adapter = AppAdapter(ctx, rows)
                // Setting a fresh adapter resets the spinner to position 0 — restore the
                // user's last pick (by PACKAGE, not index: the list can reorder/shrink when
                // apps are (un)installed between loads). Without this, every tab switch /
                // resume snapped the picker back to the first app in the daemon's list.
                // Arm the echo filter with the position we settle at so the listener can
                // tell this programmatic change apart from a real user pick.
                pendingSpinnerEcho = if (appPackages.isEmpty()) -1 else restoreSpinnerSelection()
                // Now that the spinner selection is settled, restore THAT app's saved window (the
                // one-shot restore in onViewCreated ran before this async load populated the list,
                // so it always no-op'd — this is where the per-app geometry actually lands).
                // Resolved explicitly rather than via geometryPackage(), which reports the app the
                // box is ALREADY bound to and would therefore never follow the settled selection.
                val cast = castPackage
                restoreWindowFor(if (casting && cast.isNotEmpty()) cast else selectedPackage())
                setBusy(false); updateButtons()
            }
        }
    }

    /** Re-select the persisted last-picked package in the (just rebuilt) spinner. Returns
     *  the position the spinner settles at — the saved app's index, or 0 (the adapter-reset
     *  default) when nothing is saved / the saved app is gone from the list (uninstalled). */
    private fun restoreSpinnerSelection(): Int {
        val saved = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(K_PICKED_PKG, null)
        val idx = if (saved != null) appPackages.indexOf(saved) else -1
        if (idx > 0) spinnerApps?.setSelection(idx)
        return if (idx >= 0) idx else 0
    }

    /** Remember the user's spinner pick so the selection survives tab switches, screen
     *  re-entry and app restarts (loadApps rebuilds the adapter each resume). */
    private fun persistSpinnerSelection(pkg: String) {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(K_PICKED_PKG, pkg)?.apply()
    }

    private fun onCastClicked() {
        if (layoutActive || layoutBusy) {
            notifyToast(getString(R.string.cluster_layouts_stop_first), AppToast.Kind.ERROR)
            return
        }
        if (diLink5ModeRequired) {
            notifyToast(
                getString(R.string.projection_dilink5_mode_required),
                AppToast.Kind.ERROR
            )
            return
        }
        val idx = spinnerApps?.selectedItemPosition ?: -1
        if (idx < 0 || idx >= appPackages.size) return
        val pkg = appPackages[idx]
        setBusy(true)
        // A cast can take a minute to verify on DI5; one request at a time.
        castInFlight = true
        updateButtons()
        submitIo {
            val result = postCast(pkg)
            val status = if (result.success) fetchStatus() else null
            casting = result.success
            // Bind the box to this app immediately, rather than waiting for the next status poll
            // (up to STATUS_POLL_MS away): between the two, `casting` is already true, so a drag or
            // preset would otherwise be keyed to the spinner pick instead of the cast target.
            // A failed cast leaves it empty so nothing is attributed to a cast that never started.
            castPackage = if (result.success) pkg else ""
            postMain {
                castInFlight = false
                setBusy(false); updateButtons()
                if (status != null) applyStatus(status)
                // applyStatus rebinds only when castPackage changes, and it was just set above,
                // so a box still bound to a previously browsed app must be rebound here.
                if (result.success && boxPackage != pkg) restoreWindowFor(pkg)
                if (!result.success) {
                    // Distinguish "app no longer installed" from a generic failure so the
                    // user knows to pick another (and refresh the picker to drop it).
                    when (result.reason) {
                        "not_installed" -> {
                            notifyToast(
                                getString(R.string.projection_app_uninstalled),
                                AppToast.Kind.ERROR
                            )
                            loadApps()
                        }
                        "dilink5_mode_required" -> {
                            diLink5ModeRequired = true
                            updateButtons()
                            notifyToast(
                                getString(R.string.projection_dilink5_mode_required),
                                AppToast.Kind.ERROR
                            )
                        }
                        "oem_cluster_takeover" -> {
                            // The daemon already tried every shared cluster surface;
                            // only a car-side setting can stop the OEM app re-fronting.
                            val message = getString(R.string.projection_oem_cluster_takeover)
                            setStatus(message)
                            setStatusDot(R.drawable.status_dot_offline)
                            notifyToast(message, AppToast.Kind.ERROR)
                        }
                        else -> {
                            notifyToast(
                                result.error.ifEmpty {
                                    getString(R.string.projection_cast_failed)
                                },
                                AppToast.Kind.ERROR
                            )
                        }
                    }
                }
            }
            if (result.success) {
                // Remember this as the auto-start candidate (last-cast-wins). Persisted to
                // the daemon-readable unified config so ACC-on auto-start can cast it.
                persistAutoStartPackage(pkg)
                // Arm the mirror onto the surface once the cast is up (short settle so the
                // fission display materialises before the first projection).
                try { Thread.sleep(600) } catch (_: InterruptedException) {}
                postMain { if (alive && mirrorRequested && casting) armMirror() }
            }
        }
    }

    private fun onStopClicked() {
        val stoppingLayout = layoutActive || layoutBusy
        if (stoppingLayout) layoutBusy = true
        setBusy(true)
        adjusting = false
        casting = false
        // Clear the geometry binding with the cast: onPause persists the box, and doing so after a
        // stop must not write the (now-ended) cast app's rect under a package it no longer describes.
        castPackage = ""
        castSessionGeneration = 0L
        activeHostedBounds = null
        commitSeq++
        cancelPendingMirrorRestart()
        detachMirror()
        submitIo {
            if (stoppingLayout) {
                httpPostSuccess("/api/cluster/layouts/stop", JSONObject())
            } else postStop()
            mirrorMode = MODE_STOPPED
            postMain {
                setBusy(false); updateButtons()
                // Idle text, neutral dot and bounds mode together, so the pill can't keep the live dot.
                renderMirrorState(MODE_STOPPED)
            }
        }
    }


    // ── Status polling ───────────────────────────────────────────────────────────────

    private fun startStatusPolling(requestGeneration: Long) {
        stopStatusPolling()
        val r = object : Runnable {
            override fun run() {
                if (!isMirrorRequestCurrent(requestGeneration)) return
                val poll = this
                submitIo {
                    // Cast state + real cluster panel aspect come from the daemon over HTTP;
                    // the mirror's own state ([mirrorMode]) is set by the Binder client when
                    // we attach/reproject, so we don't poll it here.
                    val st = fetchStatus()
                    postMain {
                        if (!isMirrorRequestCurrent(requestGeneration)
                            || statusPoll !== poll) return@postMain
                        if (st != null) applyStatus(st)
                        // Schedule from completion, not dispatch, so a slow daemon cannot build
                        // an unbounded queue of stale status calls on the single HTTP executor.
                        main.postDelayed(poll, STATUS_POLL_MS)
                    }
                }
            }
        }
        statusPoll = r
        main.postDelayed(r, STATUS_POLL_MS)
    }

    private fun isMirrorRequestCurrent(requestGeneration: Long): Boolean =
        alive && mirrorRequested && mirrorRequestGeneration == requestGeneration

    private fun applyStatus(st: Status) {
        val wasCasting = casting
        val previousCastPackage = castPackage
        val previousCastGeneration = castSessionGeneration
        val layoutChanged = layoutActive != st.layoutActive || layoutGeneration != st.layoutGeneration
        layoutActive = st.layoutActive
        layoutBusy = st.layoutBusy
        layoutGeneration = st.layoutGeneration
        casting = st.casting || layoutActive
        if (layoutActive) adjusting = false
        diLink5ModeRequired = st.diLink5ModeRequired
        castPackage = st.castPackage
        castSessionGeneration = st.castSessionGeneration
        resolvedProjectionTransport = st.clusterTransport
        activeProjectionPath = st.projectionPathActive
        activeHostedBounds = st.hostedProjectionBounds
        if (previousCastPackage != castPackage || previousCastGeneration != castSessionGeneration) {
            commitSeq++ // late replies/retries cannot acquire the replacement session's token
        }
        // Track the persisted transport choice + the daemon's live resolution.
        // Poll-driven (not just set-on-click) so out-of-band edits (config file,
        // another client) and post-restart resolutions stay reflected here.
        if (!transportWriteInFlight) {
            if (st.clusterTransportConfigured.isNotEmpty()) {
                configuredTransport = st.clusterTransportConfigured
                setTransportChecked(st.clusterTransportConfigured)
            }
            setTransportStatusLine(st.clusterTransport, st.clusterTransportSource)
        }
        configuredProjectionPath = st.projectionPathConfigured
        configuredProjectionTarget = st.projectionTargetConfigured
        setProjectionPathChecked(configuredProjectionPath)
        setProjectionTargetChecked(configuredProjectionTarget)
        applyProjectionSliderValues(st.projectionValues)
        updateProjectionControlMode()
        projectionPathStatus?.text = getString(
            R.string.projection_path_active,
            st.projectionPathActive,
            st.physicalDisplayId,
            st.contentDisplayId
        )
        // The cast target changed under us (a new cast started, or one ended) — rebind the box to
        // whatever is on the cluster now so it never shows/persists another app's geometry.
        var panelChanged = false
        if (st.panelW > 0 && st.panelH > 0) {
            panelChanged = st.panelW != panelW || st.panelH != panelH
            panelW = st.panelW
            panelH = st.panelH
            val aspect = st.panelW.toFloat() / st.panelH.toFloat()
            // Size the stage to the real panel aspect so the head-unit preview is panel-shaped
            // (the mirror fills it 1:1). The box view is told the cluster size so it can express
            // the window rect in cluster px — this is what makes the box panel-shaped by
            // construction and kills the old square-box regression.
            updateStageAspect(aspect)
            bounds?.setClusterSize(st.panelW, st.panelH)
        }
        if (st.casting && st.castPackage.isNotEmpty() &&
            (st.castPackage != previousCastPackage ||
                st.castSessionGeneration != previousCastGeneration ||
                (st.projectionPathActive == "hosted" && bounds?.isAdjusting() != true))
        ) restoreWindowFor(st.castPackage)

        updateButtons()
        if (!casting) {
            cancelPendingMirrorRestart()
            if (wasCasting || mirrorMode != MODE_STOPPED) detachMirror()
            if (diLink5ModeRequired) {
                val message = getString(R.string.projection_dilink5_mode_required)
                setStatus(message)
                setStatusDot(R.drawable.status_dot_offline)
                notifyStickyError(message)
                applyBoundsMode()
                return
            }
            renderMirrorState(MODE_STOPPED)
            return
        }

        // A daemon restart leaves mirrorMode ACTIVE with no live session behind it; re-arm then.
        if (mirrorMode == MODE_ACTIVE && ClusterViewMirrorClient.isSessionActive(mirrorSessionId)) {
            // A panel geometry change alters both the stage and the daemon mapping — re-cast the
            // mirror (full stop + re-arm) so the new panel size takes effect (a live reproject is
            // ignored on this firmware). updateStageAspect above already schedules a restart when it
            // resizes the stage; this covers a panel change that leaves the stage size unchanged.
            if (panelChanged || layoutChanged) restartMirrorForResize()
            renderMirrorState(MODE_ACTIVE)
        } else {
            // Covers external starts and cold projection readiness. NO_PROJECTION and
            // SERVICE_DOWN are retried on every successful status poll.
            armMirrorIfIdle()
        }
    }

    /** Retry triggers (status poll, Binder broadcast) must not queue a replay that would tear
     *  down the mirror an in-flight attach is about to bring up; the next poll retries anyway. */
    private fun armMirrorIfIdle() {
        if (mirrorAttachRunning.get()) return
        // An attach publishes its result before clearing the running flag, so re-check here.
        if (mirrorMode == MODE_ACTIVE && ClusterViewMirrorClient.isSessionActive(mirrorSessionId)) {
            return
        }
        armMirror()
    }

    private fun stopStatusPolling() {
        statusPoll?.let { main.removeCallbacks(it) }
        statusPoll = null
    }

    private fun panelAspect(): Float = panelW.toFloat() / panelH.coerceAtLeast(1).toFloat()

    /**
     * Size the stage to exactly the panel aspect, so the mirror fills it edge to edge and the
     * window frame lines up with the picture: as wide as the page allows, but no taller than
     * keeps the dock on screen below it. A short window falls back to a minimum height and
     * lets the dock scroll.
     */
    private fun updateStageAspect(aspect: Float) {
        val s = stage ?: return
        val card = stageCard ?: return
        val column = content ?: return
        val viewport = scroll ?: return
        if (!aspect.isFinite() || aspect <= 0f) return
        val columnWidth = column.width - column.paddingLeft - column.paddingRight
        // Not measured yet: the viewport's first layout re-runs this (wireStageLayout).
        if (columnWidth <= 0 || viewport.height <= 0) return
        val density = s.resources.displayMetrics.density
        val dockSpace = dock?.let { d ->
            d.height + ((d.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0)
        } ?: 0
        val heightCap = (viewport.height - column.paddingTop - dockSpace - STAGE_PEEK_DP * density)
            .coerceAtLeast(MIN_STAGE_HEIGHT_DP * density)
        val width = minOf(columnWidth.toFloat(), heightCap * aspect).roundToInt()
        val height = (width / aspect).roundToInt()
        val cardParams = card.layoutParams ?: return
        val stageParams = s.layoutParams ?: return
        if (cardParams.width == width && stageParams.height == height) return
        prepareForSurfaceResize()
        cardParams.width = width
        card.layoutParams = cardParams
        stageParams.height = height
        s.layoutParams = stageParams
        s.invalidateOutline()
        texture?.invalidateOutline()
        scheduleMirrorRestart()
    }

    /** Full screen: the rail, app bar and settings step aside and the stage takes the window. */
    private fun setTheater(on: Boolean) {
        if (theater == on) return
        if (on && (!alive || !isResumed)) return
        theater = on
        theaterBackCallback.isEnabled = on
        // The presets and portrait's Resize button live in the settings that are hidden.
        if (on && adjusting) toggleAdjust()
        (activity as? MainActivity)?.setContentImmersive(on)
        settings?.visibility = if (on) View.GONE else View.VISIBLE
        content?.let { c ->
            if (on) {
                val pad = (THEATER_PADDING_DP * c.resources.displayMetrics.density).roundToInt()
                c.setPadding(pad, pad, pad, pad)
                c.gravity = Gravity.CENTER_VERTICAL
            } else {
                c.setPadding(contentPadding[0], contentPadding[1], contentPadding[2], contentPadding[3])
                c.gravity = Gravity.TOP
            }
        }
        scroll?.scrollTo(0, 0)
        refreshFullscreenButton()
        stage?.post { if (alive) updateStageAspect(panelAspect()) }
    }

    private fun refreshFullscreenButton() {
        val b = btnFullscreen ?: return
        // Runs on every status poll; a fresh icon Drawable would relayout the page each time.
        if (fullscreenButtonTheater != theater) {
            fullscreenButtonTheater = theater
            b.setIconResource(if (theater) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
            val label = getString(
                if (theater) R.string.projection_fullscreen_exit else R.string.projection_fullscreen_enter)
            b.contentDescription = label
            b.tooltipText = label
        }
        // Exit stays reachable even if the cast ends while full screen.
        b.isEnabled = casting || theater
    }

    /** Idle hint, status pill and progress bar over the stage, from the current state. */
    private fun refreshStageOverlay() {
        emptyState?.visibility = if (casting) View.GONE else View.VISIBLE
        // While idle the hint already says what a neutral pill would.
        hud?.visibility =
            if (!casting && statusDotRes == R.drawable.status_dot_neutral) View.GONE else View.VISIBLE
        val connecting = casting && mirrorMode != MODE_ACTIVE
                && mirrorMode != MODE_UNSUPPORTED && mirrorMode != MODE_SERVICE_DOWN
        // castInFlight too: an app-list load finishing mid-cast clears [busy].
        progress?.visibility =
            if (busy || castInFlight || connecting) View.VISIBLE else View.GONE
    }

    /** Bring the pill back after a status change; over a live mirror it fades after a moment. */
    private fun revealHud() {
        val h = hud ?: return
        main.removeCallbacks(hudFade)
        h.animate().cancel()
        h.alpha = 1f
        if (casting && mirrorMode == MODE_ACTIVE && !adjusting) main.postDelayed(hudFade, HUD_HOLD_MS)
    }

    private fun clipMirrorToCard() {
        val radius = resources.getDimension(R.dimen.dashboard_modern_radius)
        val provider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        stage?.let {
            it.outlineProvider = provider
            it.clipToOutline = true
        }
        texture?.let {
            it.outlineProvider = provider
            it.clipToOutline = true
        }
    }

    /** Render the mirror-state text + box mode from a MODE_* code (set by the Binder client). */
    private fun renderMirrorState(mode: Int) {
        // Don't stomp the "drag to move" hint while the user is actively adjusting.
        if (!adjusting) {
            when (mode) {
                MODE_ACTIVE -> {
                    lastStickyError = null
                    setStatus(getString(R.string.projection_status_live))
                    setStatusDot(R.drawable.status_dot_online)
                }
                MODE_NO_PROJECTION -> {
                    setStatus(
                        if (casting) getString(R.string.projection_status_connecting)
                        else getString(R.string.projection_status_none))
                    setStatusDot(
                        if (casting) R.drawable.status_dot_starting
                        else R.drawable.status_dot_neutral)
                }
                MODE_UNSUPPORTED -> {
                    setStatus(
                        if (casting) getString(R.string.projection_status_mirror_unavailable)
                        else getString(R.string.projection_status_idle))
                    setStatusDot(R.drawable.status_dot_offline)
                    notifyStickyError(getString(R.string.projection_status_unsupported))
                }
                MODE_SERVICE_DOWN -> {
                    setStatus(
                        if (casting) getString(R.string.projection_status_mirror_unavailable)
                        else getString(R.string.projection_status_idle))
                    setStatusDot(R.drawable.status_dot_offline)
                    notifyStickyError(getString(R.string.projection_status_service_down))
                }
                else -> {
                    lastStickyError = null
                    setStatus(
                        if (casting) getString(R.string.projection_status_connecting)
                        else getString(R.string.projection_status_idle))
                    setStatusDot(
                        if (casting) R.drawable.status_dot_starting
                        else R.drawable.status_dot_neutral)
                }
            }
        }
        applyBoundsMode()
        refreshStageOverlay()
        // Repositioning acts on the CLUSTER window (via the daemon), so it's available whenever an
        // app is cast — even before the head-unit mirror preview comes up. The presets + aspect
        // lock follow the same rule.
        btnAdjust?.isEnabled = casting && !layoutActive && !layoutBusy
        presetGroup?.isEnabled = casting && !layoutActive && !layoutBusy
        aspectLockSwitch?.isEnabled = casting && !layoutActive && !layoutBusy
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────────

    private fun updateButtons() {
        btnCast?.isEnabled =
            !casting && !diLink5ModeRequired && !layoutActive && !layoutBusy
                && appPackages.isNotEmpty() && !castInFlight
        btnStop?.isEnabled = casting || layoutBusy
        // One slot: Cast while idle, Stop while casting.
        btnCast?.visibility = if (casting || layoutBusy) View.GONE else View.VISIBLE
        btnStop?.visibility = if (casting || layoutBusy) View.VISIBLE else View.GONE
        if (!casting || layoutActive || layoutBusy) btnAdjust?.isEnabled = false
        refreshFullscreenButton()
        refreshStageOverlay()
    }

    private fun setStatus(text: String) {
        val tv = statusText ?: return
        if (tv.text?.toString() == text) return
        tv.text = text
        revealHud()
    }

    private fun setStatusDot(@DrawableRes dot: Int) {
        statusDot?.setBackgroundResource(dot)
        if (statusDotRes == dot) return
        statusDotRes = dot
        refreshStageOverlay()
        revealHud()
    }

    private fun notifyToast(message: String, kind: AppToast.Kind) {
        appToast?.show(message, kind)
    }

    private fun notifyStickyError(message: String) {
        if (message == lastStickyError) return
        lastStickyError = message
        appToast?.show(message, AppToast.Kind.ERROR)
    }

    private fun setBusy(busy: Boolean) {
        this.busy = busy
        refreshStageOverlay()
    }

    private fun postMain(block: () -> Unit) {
        if (!alive) return
        main.post {
            if (!alive) return@post
            // Guard every main-thread callback (status render, aspect apply, button/state
            // updates): a non-critical feature screen must never crash the whole app on an
            // unexpected throw. Log + swallow so it degrades instead.
            try { block() } catch (t: Throwable) {
                android.util.Log.e("ProjectionFragment", "main callback failed", t)
            }
        }
    }

    /** Queue [block] on the HTTP executor. Returns true iff it was actually accepted, so callers
     *  holding a gate (see [resizeInFlight]) can release it when the work will never run. */
    private fun submitIo(block: () -> Unit): Boolean {
        return try {
            if (io.isShutdown) false else { io.execute(block); true }
        } catch (_: RejectedExecutionException) { false }
    }

    private fun submitMirror(block: () -> Unit) {
        try { if (!mirrorIo.isShutdown) mirrorIo.execute(block) }
        catch (_: RejectedExecutionException) {}
    }

    private fun submitClusterInput(block: () -> Unit) {
        try {
            if (!clusterInputIo.isShutdown) clusterInputIo.execute(block)
        } catch (_: RejectedExecutionException) {}
    }

    // ── Per-app window persistence (survives leave/return + app restart) ─────────────
    // The cluster window rect is remembered PER cast package (normalized 0..1 of the cluster) so
    // each app reopens at the size/position the user last gave it. Keyed by package so switching
    // apps restores that app's own geometry.

    /** Restore the box to {@code pkg}'s saved window, or to the FULL default if that app has none.
     *  Resetting to FULL on a no-saved app is deliberate: without it, switching from a resized app
     *  A to a never-resized app B would leave A's box showing over B — and the next persist/onPause
     *  would then write A's geometry under B's key (the audited cross-key bug). Posted so it runs
     *  after layout when width/height are known. Safe if the view is torn down (guards on `bounds`
     *  + alive). */
    private fun restoreWindowFor(pkg: String?) {
        if (layoutActive || layoutBusy) return
        val b = bounds ?: return
        b.post {
            if (!alive || bounds !== b || layoutActive || layoutBusy) return@post
            if (casting && !castPackage.isNullOrEmpty() && castPackage != pkg) return@post
            // Bind the box to this app BEFORE touching the rect, so any persist triggered by what
            // follows (applyPreset emits a commit) is keyed to the app the rect now describes.
            boxPackage = pkg ?: ""
            if (reflectHostedProjectionWindow(
                    casting && activeProjectionPath == "hosted",
                    activeHostedBounds,
                    reflect = { actual ->
                        b.setWindowRect(actual[0], actual[1], actual[2], actual[3])
                    },
                )
            ) return@post
            val n = if (pkg != null) loadNormalizedWindow(pkg) else null
            if (n != null) {
                // setNormalizedWindow updates the box WITHOUT emitting (so it can't clobber the
                // saved value). The box is the cast app's freeform bounds now, so apply those
                // bounds to the cluster app directly (the mirror is full-cluster and unaffected).
                b.setNormalizedWindow(n[0], n[1], n[2], n[3])
                val w = b.currentWindow()
                // A restore is authoritative (not a drag frame) — commit so the full ladder runs
                // and we learn whether AMS actually honoured this app's remembered rect.
                if (casting && !projectionUsesDiLink5Controls(resolvedProjectionTransport)) {
                    submitCommit(w.left, w.top, w.right, w.bottom)
                }
            } else {
                // No LOCAL record for this app. Before falling back to FULL — which emits a commit
                // and would therefore DELETE the daemon-side box — check whether the daemon still
                // has one. The two stores can legitimately diverge (app data cleared, a box saved
                // by a different surface), and treating "no local prefs" as "user wants full" would
                // silently discard a scale the headless casts are still using.
                submitIo {
                    val remote = if (pkg != null) fetchClusterWindow(pkg) else null
                    postMain {
                        if (!alive || bounds !== b || boxPackage != (pkg ?: "")) return@postMain
                        if (reflectHostedProjectionWindow(
                                casting && activeProjectionPath == "hosted",
                                activeHostedBounds,
                                reflect = { actual ->
                                    b.setWindowRect(actual[0], actual[1], actual[2], actual[3])
                                },
                            )
                        ) return@postMain
                        // Unknown is not "nothing saved": FULL would delete the daemon's box.
                        if (remote === WINDOW_LOOKUP_FAILED) return@postMain
                        if (remote != null) {
                            b.setNormalizedWindow(remote[0], remote[1], remote[2], remote[3])
                            val w = b.currentWindow()
                            // Adopt it locally so the box survives without another round-trip.
                            persistWindowPrefsOnly(pkg!!, w.left, w.top, w.right, w.bottom)
                            if (casting && !projectionUsesDiLink5Controls(resolvedProjectionTransport)) {
                                submitCommit(w.left, w.top, w.right, w.bottom)
                            }
                        } else {
                            // Genuinely nothing saved anywhere → the FULL-panel default.
                            b.applyPreset(ProjectionBoundsGeometry.PRESET_FULL, notifyChange = false)
                        }
                    }
                }
            }
        }
    }

    /** Read a package's saved box (panel fractions) from the daemon store, null if none, or
     *  [WINDOW_LOOKUP_FAILED] when the store could not be read. */
    private fun fetchClusterWindow(pkg: String): FloatArray? {
        return try {
            val body = httpGet("/api/settings/unified") ?: return WINDOW_LOOKUP_FAILED
            val windows = JSONObject(body).optJSONObject("config")
                ?.optJSONObject("projection")?.optJSONObject("windows") ?: return null
            val rect = windows.optJSONObject(pkg) ?: return null
            val l = rect.optDouble("l", Double.NaN); val t = rect.optDouble("t", Double.NaN)
            val r = rect.optDouble("r", Double.NaN); val b = rect.optDouble("b", Double.NaN)
            if (l.isNaN() || t.isNaN() || r.isNaN() || b.isNaN()) return null
            if (r <= l || b <= t) return null
            floatArrayOf(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        } catch (_: Throwable) { WINDOW_LOOKUP_FAILED }
    }

    /** Persist the CURRENT window rect (cluster px) for the selected package, normalized. */
    /** The package the box's geometry belongs to: the LIVE CAST app while casting, else the
     *  spinner pick (so pre-cast adjustments are still remembered for the app about to be cast).
     *  Using the spinner while a cast is live is the bug this exists to prevent — the user can
     *  browse the picker mid-cast, and the box must keep describing the app on the cluster. */
    private fun geometryPackage(): String? {
        // The box's rect is only ever set by restoreWindowFor, which records the app it bound to.
        // Trust THAT over any live inference: while a cast runs the spinner can be browsed freely
        // (the pick is deliberately not applied to the box), and after a Stop `casting`/`castPackage`
        // are cleared while the box still shows the stopped app's rect — so inferring the key from
        // the spinner would write app X's geometry under app Z, and the daemon store every headless
        // cast reads would then open Z at X's scale on ACC-on / key-mapping / automation casts.
        val bound = boxPackage
        if (bound.isNotEmpty()) return bound
        val cast = castPackage
        if (casting && cast.isNotEmpty()) return cast
        return selectedPackage()
    }

    /** Write ONLY the local prefs copy — used when adopting a box that came FROM the daemon,
     *  where echoing it back would be a pointless round-trip. */
    private fun persistWindowPrefsOnly(pkg: String, l: Int, t: Int, r: Int, bot: Int) {
        val pw = panelW.coerceAtLeast(1).toFloat()
        val ph = panelH.coerceAtLeast(1).toFloat()
        val sp = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        sp.edit()
            .putFloat(winKey(pkg, "l"), l / pw).putFloat(winKey(pkg, "t"), t / ph)
            .putFloat(winKey(pkg, "r"), r / pw).putFloat(winKey(pkg, "b"), bot / ph)
            .apply()
    }

    private fun persistWindow(l: Int, t: Int, r: Int, bot: Int) {
        if (layoutActive || layoutBusy) return
        val pkg = geometryPackage() ?: return
        val pw = panelW.coerceAtLeast(1).toFloat()
        val ph = panelH.coerceAtLeast(1).toFloat()
        persistWindowPrefsOnly(pkg, l, t, r, bot)
        // These prefs live in the APP uid; the daemon (uid 2000) cannot read them, so a box
        // adjusted with NO cast active would be invisible to a later headless cast (ACC-on
        // auto-start / key mapping / automation) and the app would come up fullscreen. While
        // casting, cluster-resize already persists the daemon-side copy — so only mirror it
        // here for the not-casting case, and skip it before the real panel size is known.
        if (!casting && panelW > 1 && panelH > 1) {
            submitIo { postClusterWindow(pkg, l / pw, t / ph, r / pw, bot / ph) }
        }
    }

    /** Save a per-app box (panel FRACTIONS) into the daemon-readable unified config, so a cast
     *  started with no Projection screen in the loop still lands at the user's chosen scale. */
    private fun postClusterWindow(pkg: String, fl: Float, ft: Float, fr: Float, fb: Float) {
        httpPostSuccess("/api/vehicle/cluster-window",
            JSONObject().put("package", pkg)
                .put("l", fl.toDouble()).put("t", ft.toDouble())
                .put("r", fr.toDouble()).put("b", fb.toDouble()))
    }

    /** Load a package's saved normalized [l,t,r,b] window, or null if none. */
    private fun loadNormalizedWindow(pkg: String): FloatArray? {
        val sp = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        if (!sp.contains(winKey(pkg, "r"))) return null
        return floatArrayOf(
            sp.getFloat(winKey(pkg, "l"), 0f), sp.getFloat(winKey(pkg, "t"), 0f),
            sp.getFloat(winKey(pkg, "r"), 1f), sp.getFloat(winKey(pkg, "b"), 1f)
        )
    }

    private fun selectedPackage(): String? {
        val idx = spinnerApps?.selectedItemPosition ?: -1
        return if (idx in appPackages.indices) appPackages[idx] else null
    }

    private fun winKey(pkg: String, axis: String) = "win_${pkg}_$axis"

    // ── Daemon HTTP (all off-main-thread) ───────────────────────────────────────────

    private fun fetchApps(pm: PackageManager?, res: Resources, iconPx: Int): List<AppEntry> {
        val apps = ArrayList<AppEntry>()
        try {
            val body = httpGet("/api/vehicle/cluster-apps") ?: return apps
            val arr = JSONObject(body).optJSONArray("apps") ?: return apps
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("package", "")
                if (pkg.isEmpty()) continue
                apps.add(AppEntry(o.optString("label", pkg), pkg, appIcon(pm, res, iconPx, pkg)))
            }
        } catch (_: Throwable) {}
        return apps
    }

    /** The app's launcher icon at picker size, cached across reloads. Runs on [io] only. */
    private fun appIcon(pm: PackageManager?, res: Resources, sizePx: Int, pkg: String): Drawable? {
        appIcons[pkg]?.let { return it }
        val source = try { pm?.getApplicationIcon(pkg) } catch (_: Throwable) { null } ?: return null
        // Launcher icons decode at full density; keep only what the row draws.
        val icon = try {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            source.setBounds(0, 0, sizePx, sizePx)
            source.draw(Canvas(bitmap))
            BitmapDrawable(res, bitmap)
        } catch (_: Throwable) { source }
        appIcons[pkg] = icon
        return icon
    }

    private class AppEntry(val label: String, val pkg: String, val icon: Drawable?) {
        override fun toString() = label
    }

    /** Picker rows with the app's icon; the collapsed field and the dropdown share one bind. */
    private class AppAdapter(context: Context, private val entries: List<AppEntry>) :
        ArrayAdapter<AppEntry>(context, R.layout.item_projection_spinner, entries) {

        private val inflater = LayoutInflater.from(context)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind(convertView ?: inflater.inflate(R.layout.item_projection_spinner, parent, false),
                position)

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            bind(convertView ?: inflater.inflate(R.layout.item_projection_app, parent, false),
                position)

        private fun bind(row: View, position: Int): View {
            val entry = entries.getOrNull(position) ?: return row
            row.findViewById<TextView>(R.id.projectionAppLabel)?.text = entry.label
            row.findViewById<ImageView>(R.id.projectionAppIcon)?.let { iv ->
                iv.visibility = if (entry.pkg.isEmpty()) View.GONE else View.VISIBLE
                // A Drawable keeps one set of bounds, so each row draws its own copy.
                val icon = entry.icon?.constantState?.newDrawable(iv.resources)?.mutate()
                    ?: entry.icon
                if (icon != null) iv.setImageDrawable(icon)
                else iv.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            return row
        }
    }

    private class Status(
        val casting: Boolean,
        val panelW: Int,
        val panelH: Int,
        val castPackage: String,
        val diLink5ModeRequired: Boolean,
        val clusterTransport: String,
        val clusterTransportSource: String,
        val clusterTransportConfigured: String,
        val projectionPathConfigured: String,
        val projectionTargetConfigured: String,
        val projectionValues: IntArray,
        val projectionPathActive: String,
        val physicalDisplayId: Int,
        val contentDisplayId: Int,
        val layoutActive: Boolean,
        val layoutBusy: Boolean,
        val layoutGeneration: Long,
        val castSessionGeneration: Long,
        val hostedProjectionBounds: IntArray?,
    )

    private fun fetchStatus(): Status? {
        return try {
            val body = httpGet("/api/vehicle/cluster-mirror-status") ?: return null
            val j = JSONObject(body)
            if (!j.optBoolean("success", false)) return null
            // Mirror the daemon's admission rule: a unit whose CLUSTER
            // TRANSPORT is already DI5 (firmware-detected or config override)
            // can cast without the dilink5 camera mode, so the mode nag only
            // applies when the transport stayed legacy.
            val modeRequired =
                j.optBoolean("dilink5Hardware", false) &&
                        !j.optString("vehicleMode", "default")
                            .equals("dilink5", ignoreCase = true) &&
                        !j.optString("clusterTransport", "legacy")
                            .equals("dilink5", ignoreCase = true)
            val layouts = j.optJSONObject("layouts")
            val layoutsActive = j.optBoolean("layoutActive", layouts?.optBoolean("active", false) ?: false)
            Status(
                j.optBoolean("casting", false),
                if (layoutsActive) layouts?.optInt("width", j.optInt("panelW", 0)) ?: j.optInt("panelW", 0)
                    else j.optInt("panelW", 0),
                if (layoutsActive) layouts?.optInt("height", j.optInt("panelH", 0)) ?: j.optInt("panelH", 0)
                    else j.optInt("panelH", 0),
                j.optString("castPackage", ""),
                modeRequired,
                j.optString("clusterTransport", ""),
                j.optString("clusterTransportSource", ""),
                j.optString("clusterTransportConfigured", "auto"),
                j.optString("projectionPathConfigured", "auto"),
                j.optString("projectionTargetConfigured", "standard"),
                intArrayOf(
                    j.optInt("di5HostedWidthPercent", 100),
                    j.optInt("di5HostedHeightPercent", 100),
                    j.optInt("di5HostedXOffsetPercent", 50),
                    j.optInt("di5HostedYOffsetPercent", 50),
                    j.optInt("di5HostedContentScalePercent", 100),
                    j.optInt("di5DirectScalePercent", 100)
                ),
                j.optString("projectionPathActive", "none"),
                j.optInt("physicalDisplayId", -1),
                j.optInt("contentDisplayId", -1),
                layoutsActive,
                j.optBoolean("layoutBusy", layouts?.optBoolean("busy", false) ?: false),
                j.optLong("layoutGeneration", layouts?.optLong("generation", 0L) ?: 0L),
                j.optLong("castSessionGeneration", 0L),
                j.optJSONObject("hostedProjectionBounds")?.let {
                    intArrayOf(it.optInt("l", -1), it.optInt("t", -1),
                        it.optInt("r", -1), it.optInt("b", -1))
                },
            )
        } catch (_: Throwable) { null }
    }

    private class CastResult(
        val success: Boolean,
        val reason: String,
        val error: String
    )

    private fun postCast(pkg: String): CastResult {
        var conn: java.net.HttpURLConnection? = null
        return try {
            // DI5 waits for compositor enable, display discovery, launch and
            // resumed-on-display verification before returning success.
            conn = DaemonHttpClient.open("/api/vehicle/cluster-cast", "POST", 2000, 70000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val os: OutputStream = conn.outputStream
            os.write(JSONObject().put("package", pkg).toString().toByteArray()); os.flush(); os.close()
            if (conn.responseCode != 200) return CastResult(false, "", "")
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(resp)
            CastResult(
                j.optBoolean("success", false),
                j.optString("reason", ""),
                j.optString("error", "")
            )
        } catch (_: Throwable) {
            CastResult(false, "", "")
        } finally {
            conn?.disconnect()
        }
    }

    private fun postStop() { httpPostSuccess("/api/vehicle/cluster-stop", JSONObject()) }

    /** Resize the cast app ON the cluster to a cluster-px box. No-op server-side unless the
     *  freeform feature flag is enabled + a cast is active, so it's safe to fire on any commit.
     *
     *  @param commit true = authoritative apply (full ladder + AMS verification) on gesture
     *    release / preset / restore; false = a live drag frame taking the cheap bounds-only path. */
    private fun postClusterResize(l: Int, t: Int, r: Int, bot: Int, commit: Boolean,
                                  seq: Long = 0L, pkg: String = "", attempt: Int = 0,
                                  sessionGeneration: Long = 0L) {
        val body = httpPostForBody("/api/vehicle/cluster-resize",
            JSONObject().put("l", l).put("t", t).put("r", r).put("b", bot).put("commit", commit)
                .put("package", pkg).put("sessionGeneration", sessionGeneration))
        if (commit) applyResizeResponse(seq, body, attempt, pkg, sessionGeneration)
    }

    /** Queue an authoritative resize (release / preset / restore) for the app the box is bound
     *  to. Main thread only: the sequence number lets a slow reply for an older commit be ignored. */
    private fun submitCommit(l: Int, t: Int, r: Int, bot: Int) {
        if (layoutActive || layoutBusy) return
        val seq = ++commitSeq
        val pkg = geometryPackage() ?: ""
        val sessionGeneration = castSessionGeneration
        submitIo { postClusterResize(l, t, r, bot, commit = true, seq = seq, pkg = pkg,
            sessionGeneration = sessionGeneration) }
    }

    /** Show the rect the cluster actually holds, and say so when it refused the box. */
    private fun applyResizeResponse(
        seq: Long, body: String?, attempt: Int, requestPackage: String, requestGeneration: Long,
    ) {
        val j = try { if (body != null) JSONObject(body) else null } catch (_: Throwable) { null }
        if (j == null || !j.optBoolean("success", false)) return
        val applied = j.optBoolean("applied", false)
        val retryable = j.optBoolean("retryable", false)
        val quiet = j.optBoolean("deferred", false) || retryable
        val reason = j.optString("reason", "")
        val actual = j.optJSONObject("bounds")
        postMain {
            if (seq != commitSeq || requestPackage != geometryPackage() ||
                requestGeneration != castSessionGeneration
            ) return@postMain
            val b = bounds ?: return@postMain
            // The cast is still starting (no task or display yet): re-send the same box shortly.
            if (retryable && casting && attempt < COMMIT_RETRY_LIMIT) {
                val w = b.currentWindow()
                main.postDelayed({
                    if (alive && seq == commitSeq && casting && !b.isAdjusting()) {
                        submitIo {
                            postClusterResize(w.left, w.top, w.right, w.bottom, commit = true,
                                seq = seq, pkg = requestPackage, attempt = attempt + 1,
                                sessionGeneration = requestGeneration)
                        }
                    }
                }, COMMIT_RETRY_MS)
                return@postMain
            }
            // A refusal keeps the user's box (it is still what the next cast will ask for).
            if (applied && actual != null && !b.isAdjusting()) {
                val w = b.currentWindow()
                val l = actual.optInt("l", w.left)
                val t = actual.optInt("t", w.top)
                val r = actual.optInt("r", w.right)
                val bot = actual.optInt("b", w.bottom)
                if (l != w.left || t != w.top || r != w.right || bot != w.bottom) {
                    b.setWindowRect(l, t, r, bot)
                    val now = b.currentWindow()
                    geometryPackage()?.let {
                        persistWindowPrefsOnly(it, now.left, now.top, now.right, now.bottom)
                    }
                }
            }
            if (!applied && !quiet && reason.isNotEmpty()) {
                notifyToast(getString(R.string.projection_resize_not_applied), AppToast.Kind.WARNING)
            }
        }
    }

    // Touch: the bounds view emits a tap/swipe normalized (0..1) of the WHOLE VIEW (= whole
    // cluster, since the mirror now shows the full cluster). [lastPaneRect] is the full mirror
    // surface rect, so origin + fraction of its size gives the surface px, and the relay inverts
    // it through the mirror's published src/dst projection to cluster px (ClusterInputRelay.tap/
    // swipe → destinationToSource).
    private fun boxNormToView(nx: Double, ny: Double): DoubleArray? {
        val box = lastPaneRect
        if (box[2] <= 0 || box[3] <= 0) return null
        return doubleArrayOf(box[0] + nx * box[2], box[1] + ny * box[3])
    }

    private fun postTap(v: DoubleArray, generation: Long, epoch: Long) {
        if (epoch != ClusterViewMirrorClient.currentInputEpoch()) return
        httpPostSuccess("/api/vehicle/cluster-touch",
            JSONObject().put("type", "tap").put("sx", v[0]).put("sy", v[1])
                .put("layoutGeneration", generation))
    }

    private fun postSwipe(a: DoubleArray, b: DoubleArray, ms: Int, generation: Long, epoch: Long) {
        if (epoch != ClusterViewMirrorClient.currentInputEpoch()) return
        httpPostSuccess("/api/vehicle/cluster-touch", JSONObject().put("type", "swipe")
            .put("sx", a[0]).put("sy", a[1]).put("sx2", b[0]).put("sy2", b[1]).put("ms", ms)
            .put("layoutGeneration", generation))
    }

    private fun httpGet(path: String): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, "GET", 2000, 4000)
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) { null } finally { conn?.disconnect() }
    }

    private fun httpPostSuccess(path: String, body: JSONObject): Boolean {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, "POST", 2000, 4000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val os: OutputStream = conn.outputStream
            os.write(body.toString().toByteArray()); os.flush(); os.close()
            if (conn.responseCode != 200) return false
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(resp).optBoolean("success", false)
        } catch (_: Throwable) { false } finally { conn?.disconnect() }
    }

    /** POST returning the raw response body (null on transport/HTTP failure), for
     *  endpoints whose reply carries more than a success flag. */
    private fun httpPostForBody(path: String, body: JSONObject): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, "POST", 2000, 4000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val os: OutputStream = conn.outputStream
            os.write(body.toString().toByteArray()); os.flush(); os.close()
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) { null } finally { conn?.disconnect() }
    }

    companion object {
        private val PROJECTION_SLIDERS = listOf(
            ProjectionSliderSpec("di5HostedWidthPercent", R.id.projectionHostedWidth,
                R.id.projectionHostedWidthValue, R.string.projection_hosted_width, 20),
            ProjectionSliderSpec("di5HostedHeightPercent", R.id.projectionHostedHeight,
                R.id.projectionHostedHeightValue, R.string.projection_hosted_height, 20),
            ProjectionSliderSpec("di5HostedXOffsetPercent", R.id.projectionHostedX,
                R.id.projectionHostedXValue, R.string.projection_hosted_x_offset, 0),
            ProjectionSliderSpec("di5HostedYOffsetPercent", R.id.projectionHostedY,
                R.id.projectionHostedYValue, R.string.projection_hosted_y_offset, 0),
            ProjectionSliderSpec("di5HostedContentScalePercent", R.id.projectionHostedScale,
                R.id.projectionHostedScaleValue, R.string.projection_hosted_content_scale, 50),
            ProjectionSliderSpec("di5DirectScalePercent", R.id.projectionDirectScale,
                R.id.projectionDirectScaleValue, R.string.projection_direct_scale, 50)
        )
        private val PROJECTION_SLIDER_DEFAULTS = intArrayOf(100, 100, 50, 50, 100, 100)
        private const val SPEED_OVERLAY_KEY = com.overdrive.app.surveillance.ClusterSpeedOverlay.CONFIG_KEY
        private const val ADAS_HIDE_KEY = com.overdrive.app.surveillance.ClusterAdasController.CONFIG_KEY
        // Mirror states — MUST match ClusterViewMirrorService.STATE_* / the Binder client.
        private const val MODE_STOPPED = 0
        private const val MODE_ACTIVE = 1
        private const val MODE_NO_PROJECTION = 2
        private const val MODE_UNSUPPORTED = 3
        private const val MODE_SERVICE_DOWN = 5

        // Scaling modes — MUST match ClusterViewMirrorService.SCALE_*.
        private const val SCALE_FIT = 0
        private const val SCALE_FILL = 1
        private const val SCALE_ZOOM = 2

        private const val STATUS_POLL_MS = 1500L
        private const val MIRROR_DETACH_TIMEOUT_MS = 1000L
        private const val MIRROR_RESTART_DELAY_MS = 250L
        private const val MIRROR_PUBLISH_RETRY_MS = 3000L
        private const val COMMIT_RETRY_MS = 1000L
        private const val COMMIT_RETRY_LIMIT = 8
        private val WINDOW_LOOKUP_FAILED = FloatArray(0)

        private const val MIN_STAGE_HEIGHT_DP = 160f
        // Room under the dock so the next section still shows that the page scrolls.
        private const val STAGE_PEEK_DP = 12f
        private const val THEATER_PADDING_DP = 12f
        private const val HUD_HOLD_MS = 4000L
        private const val HUD_FADE_MS = 300L
        // The dropdown row's icon size (item_projection_app).
        private const val APP_ICON_DP = 32f

        private const val PREFS = "projection_prefs"
        private const val K_MODE = "scale_mode"
        private const val K_ASPECT_LOCK = "aspect_lock"
        private const val K_PICKED_PKG = "picked_pkg"
    }
}

internal fun projectionUsesDiLink5Controls(transport: String): Boolean =
    transport.equals("dilink5", ignoreCase = true)

/**
 * An active hosted overlay owns its physical geometry. Opening this screen cannot reinterpret a
 * remembered legacy task rectangle as a resize request; unknown bounds wait for the next poll.
 */
internal fun reflectHostedProjectionWindow(
    hostedActive: Boolean,
    actual: IntArray?,
    reflect: (IntArray) -> Unit,
): Boolean {
    if (!hostedActive) return false
    if (actual != null && actual.size == 4 &&
        actual[0] >= 0 && actual[1] >= 0 && actual[2] > actual[0] && actual[3] > actual[1]
    ) reflect(actual.copyOf())
    return true
}
