package com.overdrive.app.ui.fragment

import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.overdrive.app.R
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
    private var texture: TextureView? = null

    // The live Surface from [texture] (created on SurfaceTexture available/resized). The
    // daemon composites the cluster into it. Held only while the surface is valid.
    @Volatile private var mirrorSurface: Surface? = null
    private val surfaceLock = Any()
    @Volatile private var surfaceGeneration = 0L

    private val appPackages = ArrayList<String>()

    @Volatile private var casting = false
    // The package ACTUALLY on the cluster, per the daemon (empty when none). The resize box acts on
    // THIS app, not on the spinner selection — the two diverge the moment the user browses the app
    // list during a live cast. All geometry persistence/restore is keyed to this while casting, so a
    // drag can never reshape the cast app to a different app's remembered rect.
    @Volatile private var castPackage = ""
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
                text = "Projection is temporarily unavailable. Please reopen the app."
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
            texture = view.findViewById(R.id.projectionTexture)

            btnCast?.setOnClickListener { onCastClicked() }
            btnStop?.setOnClickListener { onStopClicked() }
            btnAdjust?.setOnClickListener { toggleAdjust() }
            wireAppSpinner()
            wireBounds()
            wireTexture()
            wireStageLayout()
            wireScaleGroup()
            wirePresetGroup()
            wireAspectLockSwitch()
            wireAutoStartSwitch()
            restoreScaleMode()
            clipMirrorToCard()
            // Per-app window restore happens in loadApps() once the spinner selection settles
            // (the app list loads async in onResume, so there's no selected package yet here).
            // Seed the stage to the default panel aspect (8:3) IMMEDIATELY so the preview pane is
            // panel-shaped from first paint — never the near-square the fixed 300dp default looks
            // like on a wide head unit while the daemon panel size is still resolving. The status
            // poll refines it to the real panel aspect once known.
            stage?.post { if (alive) updateStageAspect(panelW.toFloat() / panelH.coerceAtLeast(1).toFloat()) }
        } catch (t: Throwable) {
            android.util.Log.e("ProjectionFragment", "onViewCreated failed", t)
        }
    }

    override fun onResume() {
        super.onResume()
        if (inflateFailed) return
        mirrorRequested = true
        val requestGeneration = ++mirrorRequestGeneration
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
        refreshAutoStartState()
    }

    override fun onPause() {
        super.onPause()
        if (inflateFailed) return
        mirrorRequested = false
        mirrorRequestGeneration++
        cancelPendingMirrorRestart()
        stopStatusPolling()
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
            updateStageAspect(panelW.toFloat() / panelH.coerceAtLeast(1).toFloat())
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
        stageLayoutListener?.let { listener -> stage?.removeOnLayoutChangeListener(listener) }
        stageLayoutListener = null
        spinnerApps = null; btnCast = null; btnStop = null; btnAdjust = null
        appToast?.cancel()
        appToast = null
        lastStickyError = null
        statusText = null; statusDot = null; stage = null; bounds = null; progress = null
        scaleGroup = null; presetGroup = null; aspectLockSwitch = null
        autoStartSwitch = null; texture = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        io.shutdown()
        mirrorIo.shutdown()
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
        b.onWindowChanged = { l, t, r, bot, commit ->
            // The box is the cast app's FREEFORM BOUNDS on the cluster — NOT a head-unit preview
            // sub-rect. The mirror always shows the whole cluster and does not move with the box,
            // so a box change never re-casts the mirror.
            //
            // LIVE tracking: intermediate (commit=false) frames are forwarded too, so the app
            // visibly follows the finger on the cluster instead of jumping only on release. Those
            // take the daemon's cheap bounds-only path (no stack rescan, no verify, no shell), and
            // are DROPPED when one is already in flight (see [resizeInFlight]) so a slow daemon
            // can't build a backlog of stale rects on the single HTTP executor. The commit frame
            // is never dropped — it is the authoritative, verified apply.
            if (commit) {
                persistWindow(l, t, r, bot)
                if (casting) submitIo { postClusterResize(l, t, r, bot, commit = true) }
            } else if (casting && resizeInFlight.compareAndSet(false, true)) {
                // Release the gate if the task never actually got queued (executor shut down or
                // rejected): the `finally` below only runs for a block that RAN, so without this
                // the flag would stick true and silently disable live tracking from then on.
                if (!submitIo {
                        try { postClusterResize(l, t, r, bot, commit = false) }
                        finally { resizeInFlight.set(false) }
                    }) {
                    resizeInFlight.set(false)
                }
            }
        }
        // CONTROL taps/swipes are normalized to the WHOLE VIEW (= whole cluster) now, since the
        // mirror shows the full cluster. The relay maps full-surface px → full-cluster px through
        // the daemon's published projection (ClusterInputRelay → destinationToSource).
        b.onTap = { nx, ny -> submitIo { postTap(nx, ny) } }
        b.onSwipe = { nx1, ny1, nx2, ny2, ms -> submitIo { postSwipe(nx1, ny1, nx2, ny2, ms) } }
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

    /** Keep the stage proportional to the real panel whenever the retained Activity changes
     * width (rotation, split-screen, rail/inset relayout). Height changes are posted out of
     * the current layout pass so TextureView receives one settled resize. */
    private fun wireStageLayout() {
        val s = stage ?: return
        val listener = View.OnLayoutChangeListener {
                v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            val oldWidth = oldRight - oldLeft
            if (width > 0 && width != oldWidth) {
                v.post {
                    if (alive && stage === v) {
                        updateStageAspect(panelW.toFloat() / panelH.coerceAtLeast(1).toFloat())
                    }
                }
            }
        }
        stageLayoutListener = listener
        s.addOnLayoutChangeListener(listener)
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
                if (mirrorRequested && generation == surfaceGeneration) {
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
        bounds?.setAspectLocked(locked)
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

    /** Read both auto-start flags from the unified config so the switch reflects the saved
     *  state and the conflict dialog knows the sibling's value. */
    private fun refreshAutoStartState() {
        submitIo {
            var projOn = false
            var mapOn = false
            try {
                val body = httpGet("/api/settings/unified")
                if (body != null) {
                    val cfg = JSONObject(body).optJSONObject("config") ?: JSONObject()
                    cfg.optJSONObject("projection")?.let { projOn = it.optBoolean("autoStartOnAcc", false) }
                    cfg.optJSONObject("navMap")?.let { mapOn = it.optBoolean("autoProjectCluster", false) }
                }
            } catch (_: Throwable) {}
            roadSenseAutoProject = mapOn
            val on = projOn
            postMain { setAutoStartChecked(on) }
        }
    }

    private fun postUnified(section: String, data: JSONObject): Boolean =
        httpPostSuccess("/api/settings/unified",
            JSONObject().put("section", section).put("data", data))

    private fun toggleAdjust() {
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
        submitIo {
            val (labels, pkgs) = fetchApps()
            postMain {
                appPackages.clear(); appPackages.addAll(pkgs)
                val ctx = context ?: return@postMain
                val adapter = ArrayAdapter(ctx, R.layout.item_projection_spinner, labels)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerApps?.adapter = adapter
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
        val idx = spinnerApps?.selectedItemPosition ?: -1
        if (idx < 0 || idx >= appPackages.size) return
        val pkg = appPackages[idx]
        setBusy(true)
        submitIo {
            val result = postCast(pkg)
            casting = result.success
            // Bind the box to this app immediately, rather than waiting for the next status poll
            // (up to STATUS_POLL_MS away): between the two, `casting` is already true, so a drag or
            // preset would otherwise be keyed to the spinner pick instead of the cast target.
            // A failed cast leaves it empty so nothing is attributed to a cast that never started.
            castPackage = if (result.success) pkg else ""
            postMain {
                setBusy(false); updateButtons()
                if (!result.success) {
                    // Distinguish "app no longer installed" from a generic failure so the
                    // user knows to pick another (and refresh the picker to drop it).
                    if (result.reason == "not_installed") {
                        notifyToast(getString(R.string.projection_app_uninstalled), AppToast.Kind.ERROR)
                        loadApps()
                    } else {
                        notifyToast(getString(R.string.projection_cast_failed), AppToast.Kind.ERROR)
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
        setBusy(true)
        adjusting = false
        casting = false
        // Clear the geometry binding with the cast: onPause persists the box, and doing so after a
        // stop must not write the (now-ended) cast app's rect under a package it no longer describes.
        castPackage = ""
        cancelPendingMirrorRestart()
        detachMirror()
        submitIo {
            postStop()
            mirrorMode = MODE_STOPPED
            postMain {
                setBusy(false); updateButtons(); applyBoundsMode()
                setStatus(getString(R.string.projection_status_idle))
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
        casting = st.casting
        castPackage = st.castPackage
        // The cast target changed under us (a new cast started, or one ended) — rebind the box to
        // whatever is on the cluster now so it never shows/persists another app's geometry.
        if (st.casting && st.castPackage.isNotEmpty() && st.castPackage != previousCastPackage) {
            restoreWindowFor(st.castPackage)
        }
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

        updateButtons()
        if (!st.casting) {
            cancelPendingMirrorRestart()
            if (wasCasting || mirrorMode != MODE_STOPPED) detachMirror()
            renderMirrorState(MODE_STOPPED)
            return
        }

        if (mirrorMode == MODE_ACTIVE) {
            // A panel geometry change alters both the stage and the daemon mapping — re-cast the
            // mirror (full stop + re-arm) so the new panel size takes effect (a live reproject is
            // ignored on this firmware). updateStageAspect above already schedules a restart when it
            // resizes the stage; this covers a panel change that leaves the stage size unchanged.
            if (panelChanged) restartMirrorForResize()
            renderMirrorState(MODE_ACTIVE)
        } else {
            // Covers external starts and cold projection readiness. NO_PROJECTION and
            // SERVICE_DOWN are retried on every successful status poll.
            armMirror()
        }
    }

    private fun stopStatusPolling() {
        statusPoll?.let { main.removeCallbacks(it) }
        statusPoll = null
    }

    private fun updateStageAspect(aspect: Float) {
        val s = stage ?: return
        if (!aspect.isFinite() || aspect <= 0f) return
        if (s.width <= 0) {
            s.post { if (alive) updateStageAspect(aspect) }
            return
        }
        val density = s.resources.displayMetrics.density
        // ProjectionBoundsView keeps its 20dp handles inside the stage; retain a little extra
        // clearance so a panel-shaped pane can use the full available width.
        val inset = 24f * density
        val contentWidth = (s.width - 2f * inset).coerceAtLeast(1f)
        val desired = (contentWidth / aspect + 2f * inset).roundToInt()
        val minimum = (240f * density).roundToInt()
        val target = desired.coerceAtLeast(minimum)
        val params = s.layoutParams ?: return
        if (params.height != target) {
            prepareForSurfaceResize()
            params.height = target
            s.layoutParams = params
            s.invalidateOutline()
            texture?.invalidateOutline()
            scheduleMirrorRestart()
        }
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
                    setStatus(getString(R.string.projection_status_none))
                    setStatusDot(
                        if (casting) R.drawable.status_dot_starting
                        else R.drawable.status_dot_neutral)
                }
                MODE_UNSUPPORTED -> {
                    setStatus(
                        if (casting) getString(R.string.projection_status_none)
                        else getString(R.string.projection_status_idle))
                    setStatusDot(R.drawable.status_dot_offline)
                    notifyStickyError(getString(R.string.projection_status_unsupported))
                }
                MODE_SERVICE_DOWN -> {
                    setStatus(
                        if (casting) getString(R.string.projection_status_none)
                        else getString(R.string.projection_status_idle))
                    setStatusDot(R.drawable.status_dot_offline)
                    notifyStickyError(getString(R.string.projection_status_service_down))
                }
                else -> {
                    lastStickyError = null
                    setStatus(
                        if (casting) getString(R.string.projection_status_none)
                        else getString(R.string.projection_status_idle))
                    setStatusDot(
                        if (casting) R.drawable.status_dot_starting
                        else R.drawable.status_dot_neutral)
                }
            }
        }
        applyBoundsMode()
        // Repositioning acts on the CLUSTER window (via the daemon), so it's available whenever an
        // app is cast — even before the head-unit mirror preview comes up. The presets + aspect
        // lock follow the same rule.
        btnAdjust?.isEnabled = casting
        presetGroup?.isEnabled = casting
        aspectLockSwitch?.isEnabled = casting
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────────

    private fun updateButtons() {
        btnCast?.isEnabled = !casting && appPackages.isNotEmpty()
        btnStop?.isEnabled = casting
        if (!casting) btnAdjust?.isEnabled = false
    }

    private fun setStatus(text: String) { statusText?.text = text }

    private fun setStatusDot(@DrawableRes dot: Int) { statusDot?.setBackgroundResource(dot) }

    private fun notifyToast(message: String, kind: AppToast.Kind) {
        appToast?.show(message, kind)
    }

    private fun notifyStickyError(message: String) {
        if (message == lastStickyError) return
        lastStickyError = message
        appToast?.show(message, AppToast.Kind.ERROR)
    }

    private fun setBusy(busy: Boolean) { progress?.visibility = if (busy) View.VISIBLE else View.GONE }

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
        val b = bounds ?: return
        b.post {
            if (!alive || bounds !== b) return@post
            // Bind the box to this app BEFORE touching the rect, so any persist triggered by what
            // follows (applyPreset emits a commit) is keyed to the app the rect now describes.
            boxPackage = pkg ?: ""
            val n = if (pkg != null) loadNormalizedWindow(pkg) else null
            if (n != null) {
                // setNormalizedWindow updates the box WITHOUT emitting (so it can't clobber the
                // saved value). The box is the cast app's freeform bounds now, so apply those
                // bounds to the cluster app directly (the mirror is full-cluster and unaffected).
                b.setNormalizedWindow(n[0], n[1], n[2], n[3])
                val w = b.currentWindow()
                // A restore is authoritative (not a drag frame) — commit so the full ladder runs
                // and we learn whether AMS actually honoured this app's remembered rect.
                if (casting) submitIo { postClusterResize(w.left, w.top, w.right, w.bottom, commit = true) }
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
                        if (remote != null) {
                            b.setNormalizedWindow(remote[0], remote[1], remote[2], remote[3])
                            val w = b.currentWindow()
                            // Adopt it locally so the box survives without another round-trip.
                            persistWindowPrefsOnly(pkg!!, w.left, w.top, w.right, w.bottom)
                            if (casting) {
                                submitIo { postClusterResize(w.left, w.top, w.right, w.bottom, commit = true) }
                            }
                        } else {
                            // Genuinely nothing saved anywhere → the FULL-panel default.
                            b.applyPreset(ProjectionBoundsGeometry.PRESET_FULL)
                        }
                    }
                }
            }
        }
    }

    /** Read a package's saved box (panel fractions) from the daemon store, or null if none. */
    private fun fetchClusterWindow(pkg: String): FloatArray? {
        return try {
            val body = httpGet("/api/settings/unified") ?: return null
            val windows = JSONObject(body).optJSONObject("config")
                ?.optJSONObject("projection")?.optJSONObject("windows") ?: return null
            val rect = windows.optJSONObject(pkg) ?: return null
            val l = rect.optDouble("l", Double.NaN); val t = rect.optDouble("t", Double.NaN)
            val r = rect.optDouble("r", Double.NaN); val b = rect.optDouble("b", Double.NaN)
            if (l.isNaN() || t.isNaN() || r.isNaN() || b.isNaN()) return null
            if (r <= l || b <= t) return null
            floatArrayOf(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        } catch (_: Throwable) { null }
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

    private fun fetchApps(): Pair<List<String>, List<String>> {
        val labels = ArrayList<String>(); val pkgs = ArrayList<String>()
        try {
            val body = httpGet("/api/vehicle/cluster-apps") ?: return Pair(labels, pkgs)
            val arr = JSONObject(body).optJSONArray("apps") ?: return Pair(labels, pkgs)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("package", "")
                if (pkg.isEmpty()) continue
                pkgs.add(pkg); labels.add(o.optString("label", pkg))
            }
        } catch (_: Throwable) {}
        return Pair(labels, pkgs)
    }

    private class Status(
        val casting: Boolean, val panelW: Int, val panelH: Int, val castPackage: String)

    private fun fetchStatus(): Status? {
        return try {
            val body = httpGet("/api/vehicle/cluster-mirror-status") ?: return null
            val j = JSONObject(body)
            if (!j.optBoolean("success", false)) return null
            Status(j.optBoolean("casting", false), j.optInt("panelW", 0), j.optInt("panelH", 0),
                j.optString("castPackage", ""))
        } catch (_: Throwable) { null }
    }

    private class CastResult(val success: Boolean, val reason: String)

    private fun postCast(pkg: String): CastResult {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open("/api/vehicle/cluster-cast", "POST", 2000, 4000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val os: OutputStream = conn.outputStream
            os.write(JSONObject().put("package", pkg).toString().toByteArray()); os.flush(); os.close()
            if (conn.responseCode != 200) return CastResult(false, "")
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(resp)
            CastResult(j.optBoolean("success", false), j.optString("reason", ""))
        } catch (_: Throwable) { CastResult(false, "") } finally { conn?.disconnect() }
    }

    private fun postStop() { httpPostSuccess("/api/vehicle/cluster-stop", JSONObject()) }

    /** Resize the cast app ON the cluster to a cluster-px box. No-op server-side unless the
     *  freeform feature flag is enabled + a cast is active, so it's safe to fire on any commit.
     *
     *  @param commit true = authoritative apply (full ladder + AMS verification) on gesture
     *    release / preset / restore; false = a live drag frame taking the cheap bounds-only path. */
    private fun postClusterResize(l: Int, t: Int, r: Int, bot: Int, commit: Boolean) {
        httpPostSuccess("/api/vehicle/cluster-resize",
            JSONObject().put("l", l).put("t", t).put("r", r).put("b", bot).put("commit", commit))
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

    private fun postTap(nx: Double, ny: Double) {
        val v = boxNormToView(nx, ny) ?: return
        httpPostSuccess("/api/vehicle/cluster-touch",
            JSONObject().put("type", "tap").put("sx", v[0]).put("sy", v[1]))
    }

    private fun postSwipe(nx1: Double, ny1: Double, nx2: Double, ny2: Double, ms: Int) {
        val a = boxNormToView(nx1, ny1) ?: return
        val b = boxNormToView(nx2, ny2) ?: return
        httpPostSuccess("/api/vehicle/cluster-touch", JSONObject().put("type", "swipe")
            .put("sx", a[0]).put("sy", a[1]).put("sx2", b[0]).put("sy2", b[1]).put("ms", ms))
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

    companion object {
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

        private const val PREFS = "projection_prefs"
        private const val K_MODE = "scale_mode"
        private const val K_ASPECT_LOCK = "aspect_lock"
        private const val K_PICKED_PKG = "picked_pkg"
    }
}
