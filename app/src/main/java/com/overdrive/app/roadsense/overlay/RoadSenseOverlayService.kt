package com.overdrive.app.roadsense.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.overlay.OverlayPermissionChecker
import com.overdrive.app.roadsense.config.RoadSenseConfig
import com.overdrive.app.roadsense.warn.OverlayState
import com.overdrive.app.services.DaemonKeepaliveService

/**
 * App-side floating overlay for RoadSense (D-024). Pure RENDERER: it polls the
 * daemon-published `roadSense.overlayState` from UCM and draws the pill / card.
 * All detection + warning logic is daemon-side; this service owns only the window.
 *
 * Mirrors StatusOverlayService's proven mechanics: TYPE_APPLICATION_OVERLAY window,
 * the shared overlay-permission gate, themedContext() for day/night, drag-to-move with
 * persisted position, foreground service, rebuild on configuration change. Tap the
 * pill to expand → card; tap the card header / auto-timeout to collapse.
 *
 * The quick-toggles + confirm buttons write back to the `roadSense` config section
 * (the daemon reads them) — the only "control" this renderer does.
 */
class RoadSenseOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    // Background thread for the UCM disk read/parse — must NOT run on the main
    // looper (audit UI #3: forceReload every 400 ms on the UI thread is an ANR
    // risk over a long drive). The read happens here; view mutations are posted
    // back to [handler] (main).
    private var ioThread: android.os.HandlerThread? = null
    private var ioHandler: Handler? = null
    // Day/night-resolved context the overlay was inflated with; status colors are
    // looked up from it so they track the theme instead of being hardcoded hex.
    private var themedCtx: Context? = null

    @Volatile private var expanded = false
    // Written from the IO thread (self-stop) and the main thread (start/destroy).
    @Volatile private var pollRunnable: Runnable? = null

    // Render change-gate (perf): the poll loop posts a main-thread render() every
    // POLL_MS. render() mutates the visible pill (setColorFilter/setText/setRotation
    // → invalidate → a traversal) EVERY tick even when nothing changed, which showed
    // up as a continuous ~2.5Hz redraw of the overlay window competing with the
    // foreground map's frames. We skip the post when the render-relevant signature is
    // unchanged. The signature excludes updatedMs (it changes every daemon write even
    // when nothing visible changed) but FOLDS IN the time-derived `stale` flag (so a
    // quiet daemon still transitions Scanning→Idle past STALE_MS) and the pending
    // hazardId (so the rising-edge force-expand still fires). Read off the IO thread.
    // @Volatile: written on the IO poll thread (the change-gate) AND on the main
    // thread by createOverlay(), which nulls it to force a repaint of a freshly
    // inflated view. Without volatile the IO thread can keep a cached signature and
    // leave a re-inflated pill (relocalize / onConfigurationChanged, which call
    // createOverlay with no following render) showing all-default values.
    @Volatile private var lastRenderSig: String? = null

    // Bound views (re-bound on each (re)inflate).
    private var pillRoot: View? = null
    private var cardRoot: View? = null
    private var pillCalDot: ImageView? = null
    private var pillLabel: TextView? = null
    private var cardCalDot: ImageView? = null
    private var cardCalLabel: TextView? = null
    private var hazardArrow: ImageView? = null
    private var hazardGlow: ImageView? = null
    private var hazardDistance: TextView? = null
    private var hazardSeverity: TextView? = null
    private var toggleAudio: TextView? = null
    private var toggleVisual: TextView? = null
    private var confirmPanel: View? = null
    private var confirmAssessment: TextView? = null
    private var confirmAccept: TextView? = null
    private var confirmReject: TextView? = null
    private var confirmSevMinor: TextView? = null
    private var confirmSevModerate: TextView? = null
    private var confirmSevSevere: TextView? = null
    private var confirmTypeBreaker: TextView? = null
    private var confirmTypePothole: TextView? = null

    private var lastPendingId: String? = null
    // User's current correction selection for the visible confirm card, pre-filled
    // from the algo assessment when a new card appears (R-OVL-6). 1..3 severity;
    // type 0=breaker/1=pothole/2=unknown. Sent with the Confirm verdict.
    @Volatile private var selectedSeverity = 0
    @Volatile private var selectedType = 2

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ioThread = android.os.HandlerThread("roadsense-overlay-io").also { it.start() }
        ioHandler = Handler(ioThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The daemon can launch us via `am`, and START_STICKY can recreate us later.
        // Check the complete lifecycle policy before creating the window so a stale
        // overlay cannot flash between onCreate and onStartCommand.
        val shouldShow = try {
            RoadSenseConfig.snapshot(forceReload = true).overlayShouldShow()
        } catch (t: Throwable) {
            Log.w(TAG, "visibility config unavailable - stopping: ${t.message}")
            false
        }
        if (!shouldShow) {
            Log.i(TAG, "RoadSense disabled or overlay hidden - stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        // Self-guard the overlay permission here too (not only at the app-side
        // startIfPermitted call site): the daemon launches us via `am` (startFromDaemon)
        // on ACC-on + feature-on, which bypasses the app-side canDrawOverlays gate. If
        // permission isn't granted, addView would throw and leave a zombie foreground
        // service with no window — so stop cleanly instead.
        if (!OverlayPermissionChecker.isGranted(this)) {
            Log.w(TAG, "overlay permission not granted — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlayView == null) {
            createOverlay()
            // addView failure nulls overlayView; bail rather than leave a
            // foreground service with no window.
            if (overlayView == null) {
                Log.w(TAG, "overlay window could not be created — stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            instance = this
        }
        // Unconditional: the poller can have self-terminated on a config flip
        // while the window survived. startPolling() is itself idempotent.
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        pollRunnable?.let { ioHandler?.removeCallbacks(it) }
        pollRunnable = null
        ioThread?.quitSafely()
        ioThread = null
        ioHandler = null
        stopArrowPulse()
        removeOverlay()
    }

    /** Re-inflate the overlay against the current app locale/theme. Called when the
     *  user switches the in-app language while the overlay is live — a bare Service
     *  doesn't get onConfigurationChanged for an AppCompat per-app locale change, so
     *  the pill would otherwise stay in the previous language until the next restart.
     *  Mirrors onConfigurationChanged's rebuild (arrow-pulse reset + re-inflate). */
    private fun relocalize() {
        handler.post { rebuildOverlay() }
    }

    /** Re-inflate in place. Stops the service if the new window can't be added,
     *  rather than leaving a foreground notification with nothing on screen. */
    private fun rebuildOverlay() {
        if (overlayView == null) return
        stopArrowPulse()
        removeOverlay()
        createOverlay()
        if (overlayView == null) {
            Log.w(TAG, "overlay rebuild failed — stopping")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rebuild so day/night tokens reapply (StatusOverlayService pattern).
        // Cancel the arrow pulse FIRST (audit UI #7b): the rebuild re-inflates and
        // re-binds NEW hazardArrow/hazardGlow ImageViews, but the running animator's
        // update closure captured the OLD views — left alone it keeps mutating the
        // detached old views forever (CPU + view leak) while startArrowPulse's
        // "already running, same severity" fast-path never rebinds, so the new arrow
        // stops pulsing entirely after a single rotation. Cancelling resets the
        // pulse state so the next render rebuilds it against the freshly-bound views.
        rebuildOverlay()
    }

    // ── Window lifecycle (mirrors StatusOverlayService) ────────────────────────

    private fun createOverlay() {
        if (overlayView != null) return
        // Cache the themed context so runtime color lookups resolve against the SAME
        // day/night configuration the views were inflated with (rebuilt on config
        // change via onConfigurationChanged → removeOverlay()+createOverlay()).
        themedCtx = themedContext()
        overlayView = LayoutInflater.from(themedCtx)
            .inflate(R.layout.overlay_roadsense, null)
        bindViews()
        // Fresh view tree → force the next poll to actually render (its bound views
        // are all default until the first render paints real state).
        lastRenderSig = null

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lp.x = prefs.getInt(PREF_X, DEFAULT_X)
        lp.y = prefs.getInt(PREF_Y, DEFAULT_Y)
        layoutParams = lp

        setupInteractions()
        applyExpanded()
        try {
            windowManager.addView(overlayView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
            overlayView = null
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                persistPosition()
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        // Drop the cached themed context so a rebuild (config change) re-resolves
        // it for the new day/night configuration.
        themedCtx = null
    }

    /** Persist the overlay's current x/y so a drag survives recreation, service
     *  restarts (ACC cycle), and reboots. Called on drag-end and on teardown. */
    private fun persistPosition() {
        layoutParams?.let { lp ->
            try {
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(PREF_X, lp.x).putInt(PREF_Y, lp.y).apply()
            } catch (_: Exception) {}
        }
    }

    private fun bindViews() {
        val v = overlayView ?: return
        pillRoot = v.findViewById(R.id.pillRoot)
        cardRoot = v.findViewById(R.id.cardRoot)
        pillCalDot = v.findViewById(R.id.pillCalDot)
        pillLabel = v.findViewById(R.id.pillLabel)
        cardCalDot = v.findViewById(R.id.cardCalDot)
        cardCalLabel = v.findViewById(R.id.cardCalLabel)
        hazardArrow = v.findViewById(R.id.hazardArrow)
        hazardGlow = v.findViewById(R.id.hazardGlow)
        hazardDistance = v.findViewById(R.id.hazardDistance)
        hazardSeverity = v.findViewById(R.id.hazardSeverity)
        toggleAudio = v.findViewById(R.id.toggleAudio)
        toggleVisual = v.findViewById(R.id.toggleVisual)
        confirmPanel = v.findViewById(R.id.confirmPanel)
        confirmAssessment = v.findViewById(R.id.confirmAssessment)
        confirmAccept = v.findViewById(R.id.confirmAccept)
        confirmReject = v.findViewById(R.id.confirmReject)
        confirmSevMinor = v.findViewById(R.id.confirmSevMinor)
        confirmSevModerate = v.findViewById(R.id.confirmSevModerate)
        confirmSevSevere = v.findViewById(R.id.confirmSevSevere)
        confirmTypeBreaker = v.findViewById(R.id.confirmTypeBreaker)
        confirmTypePothole = v.findViewById(R.id.confirmTypePothole)
    }

    // ── Interactions ───────────────────────────────────────────────────────────

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false

    private fun setupInteractions() {
        // Tap pill → expand. Tap card header region → collapse. Drag either to move.
        val touch = View.OnTouchListener { _, e ->
            val lp = layoutParams ?: return@OnTouchListener false
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragged = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > TOUCH_SLOP || kotlin.math.abs(dy) > TOUCH_SLOP) dragged = true
                    lp.x = startX + dx; lp.y = startY + dy
                    try { windowManager.updateViewLayout(overlayView, lp) } catch (_: Exception) {}
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        expanded = !expanded; applyExpanded()
                    } else {
                        // Persist the dragged position NOW (not only in removeOverlay):
                        // the overlay is started/stopped across ACC cycles and can be
                        // OS-killed, so saving only on teardown loses the position on a
                        // kill. Mirror StatusOverlayService — save on every drag-end so
                        // it survives recreation, service restarts, and reboots.
                        persistPosition()
                    }
                    true
                }
                else -> false
            }
        }
        pillRoot?.setOnTouchListener(touch)
        cardRoot?.setOnTouchListener(touch)

        // Quick-toggles + confirm write back to config / daemon. The UCM writes
        // run on the IO thread (off the UI looper) per feedback_no_unified_writes_on_ui_thread.
        toggleAudio?.setOnClickListener { ioHandler?.post { toggleWarnMode(audioChip = true) } }
        toggleVisual?.setOnClickListener { ioHandler?.post { toggleWarnMode(audioChip = false) } }
        // Capture the pending id on the MAIN thread at click time and pass it into
        // the IO task, rather than re-reading the shared lastPendingId field off the
        // IO thread with no happens-before edge (audit UI #5): a stale/cleared read
        // there would send a verdict for the wrong id (daemon gates on id match) or
        // null out and silently drop the user's Accept/Reject.
        confirmAccept?.setOnClickListener {
            val id = lastPendingId
            val sev = selectedSeverity
            val type = selectedType
            ioHandler?.post { resolveConfirm(id, true, sev, type) }
        }
        confirmReject?.setOnClickListener {
            val id = lastPendingId
            ioHandler?.post { resolveConfirm(id, false, 0, -1) }
        }
        // Severity / type correction chips (R-OVL-6): adjust the pre-filled
        // assessment before Confirm. Pure main-thread selection state; the value is
        // captured at Confirm-click and sent with the verdict.
        confirmSevMinor?.setOnClickListener { selectedSeverity = 1; reflectConfirmSelection() }
        confirmSevModerate?.setOnClickListener { selectedSeverity = 2; reflectConfirmSelection() }
        confirmSevSevere?.setOnClickListener { selectedSeverity = 3; reflectConfirmSelection() }
        confirmTypeBreaker?.setOnClickListener { selectedType = 0; reflectConfirmSelection() }
        confirmTypePothole?.setOnClickListener { selectedType = 1; reflectConfirmSelection() }
    }

    /** Reflect the current severity/type selection on the confirm chips (selected
     *  chip highlighted via the same state-driven background as the warn toggles). */
    private fun reflectConfirmSelection() {
        confirmSevMinor?.isSelected = selectedSeverity == 1
        confirmSevModerate?.isSelected = selectedSeverity == 2
        confirmSevSevere?.isSelected = selectedSeverity == 3
        confirmTypeBreaker?.isSelected = selectedType == 0
        confirmTypePothole?.isSelected = selectedType == 1
    }

    private fun applyExpanded() {
        // M3 fade-through between pill and card states for a polished swap rather
        // than a hard visibility flip.
        val shown = if (expanded) cardRoot else pillRoot
        val hidden = if (expanded) pillRoot else cardRoot
        hidden?.visibility = View.GONE
        shown?.let {
            it.visibility = View.VISIBLE
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(180L).start()
        }
    }

    // ── Approach animation (R-OVL-2) ───────────────────────────────────────────
    // A single reused ValueAnimator pulses the arrow's scale+alpha. We retarget
    // its duration by distance so the throb speeds up as the hazard nears.
    private var arrowPulse: android.animation.ValueAnimator? = null
    private var arrowPulseSeverity = -1
    private var arrowPulsePeriod = -1L

    private fun startArrowPulse(meters: Int, severity: Int) {
        val arrow = hazardArrow ?: return
        // Closer ⇒ shorter period (more urgent). ~1100ms far → ~360ms very near.
        val period = (360L + (meters.coerceIn(0, 300) / 300.0 * 740L)).toLong()

        // Keep ONE long-lived animator and RETARGET its duration as the hazard
        // closes, instead of cancel+recreate on every 15 m bucket (audit UI #7):
        // recreating restarted the 0→1 cycle mid-breath, so the pulse visibly jumped
        // every bucket — not the "smoothly intensifies" beacon the design wants.
        // ValueAnimator.setDuration takes effect on the NEXT repeat cycle, so the
        // rate change is seamless. Only (re)start when there's no running animator,
        // when severity flips, or when the period change is large enough to be worth
        // a discontinuity.
        val running = arrowPulse?.isRunning == true
        val periodChangedALot = arrowPulsePeriod < 0L ||
            kotlin.math.abs(period - arrowPulsePeriod) > PULSE_PERIOD_RETARGET_MS
        if (running && severity == arrowPulseSeverity) {
            // Same beacon — just retarget the throb rate smoothly; no restart.
            if (periodChangedALot) { arrowPulse?.duration = period; arrowPulsePeriod = period }
            return
        }
        arrowPulseSeverity = severity
        arrowPulsePeriod = period

        arrowPulse?.cancel()
        arrowPulse = android.animation.ValueAnimator.ofFloat(1f, 1.18f).apply {
            duration = period
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            val glow = hazardGlow
            addUpdateListener { a ->
                // NOTE: a ~15fps throttle was tried here and REVERTED. Because the
                // throttle drops whichever tick happens to be last, it frequently
                // swallowed the animator's FINAL frame, leaving arrow/glow frozen at
                // an arbitrary mid-breath scale/alpha on a severity change (the
                // hazard-cleared path is covered by stopArrowPulse's idle restore,
                // but a severity flip is not). It also read as visible stepping at
                // the shortest period (360ms = nearest hazard, most urgent), giving
                // only ~5 applied steps per half-breath — the opposite of the
                // "smoothly intensifies" beacon the surrounding retarget logic
                // exists to protect. Any future throttle must snap to the final
                // value on cancel and stay well above the 360ms-period step budget.
                val s = a.animatedValue as Float
                arrow.scaleX = s
                arrow.scaleY = s
                // alpha throbs inversely with scale for a "beacon" feel
                val t = (s - 1f) / 0.18f
                arrow.alpha = 0.65f + t * 0.35f
                // The dial bloom breathes harder + brighter than the arrow so the
                // whole gauge pulses like a radar sweep as the hazard nears.
                glow?.let {
                    it.scaleX = 1f + t * 0.22f
                    it.scaleY = 1f + t * 0.22f
                    it.alpha = 0.45f + t * 0.45f
                }
            }
            start()
        }
    }

    private fun stopArrowPulse() {
        arrowPulse?.cancel()
        arrowPulse = null
        arrowPulseSeverity = -1
        arrowPulsePeriod = -1L
        // Restore the FULL idle visual in one place (audit UI #6) so glow alpha +
        // arrow rotation + arrow alpha don't get split across call sites and drift.
        // Idle arrow: dim alpha + rotation zeroed so it clearly recedes to an
        // inactive scanning state instead of freezing as a crisp chevron still
        // pointing at the last (now-gone) hazard's bearing.
        hazardArrow?.let { it.scaleX = 1f; it.scaleY = 1f; it.alpha = IDLE_ARROW_ALPHA; it.rotation = 0f }
        hazardGlow?.let { it.scaleX = 1f; it.scaleY = 1f; it.alpha = IDLE_GLOW_ALPHA }
    }

    // ── Poll + render ──────────────────────────────────────────────────────────

    private fun startPolling() {
        // Idempotent: a second post would orphan the first loop.
        if (pollRunnable != null) return
        // The poll loop lives on the IO thread: it does the UCM disk read/parse,
        // then posts the pure view-update onto the main thread. Keeps forceReload
        // off the UI looper (audit UI #3).
        val r = object : Runnable {
            override fun run() {
                // Lifecycle self-guard: either the RoadSense master switch or the
                // overlay preference can turn this window off while it is running.
                // This also covers remote/tunnel writes when no Activity callback runs.
                if (!RoadSenseConfig.snapshot().overlayShouldShow()) {
                    // Clear before stopping: the loop ends here, and a start that
                    // arrives before stopSelf() completes must be able to re-post.
                    pollRunnable = null
                    handler.post { stopSelf() }
                    return
                }
                val state = readState()
                val warnMode = currentWarnMode()

                // Skip the main-thread render when nothing render-relevant changed —
                // avoids a continuous ~2.5Hz pill redraw fighting the map's frames.
                //
                // NOTE: an idle-detach was tried here (remove the window after N
                // seconds with no hazard, re-add on the next hazard) and REVERTED.
                // It cost real capability for a small compositor saving: the pill is
                // the feature's only "am I alive / calibrating" affordance AND the
                // host for the quick-toggles and the drag-to-reposition handle, and
                // nothing but a hazard could bring it back — so a clean drive went
                // permanently dark with no way for the user to re-summon it. It also
                // mis-handled a mid-drive service restart (START_STICKY / a daemon
                // re-launch on a regime edge), where the grace window elapsed during
                // the cold UCM read and the overlay then stayed hidden for the whole
                // trip. Do not re-add without a user-reachable re-summon path.
                val sig = renderSignature(state, warnMode)
                if (sig != lastRenderSig) {
                    lastRenderSig = sig
                    handler.post { if (overlayView != null) render(state, warnMode) }
                }
                ioHandler?.postDelayed(this, POLL_MS)
            }
        }
        pollRunnable = r
        ioHandler?.post(r)
    }

    /**
     * Render-relevant signature for the change-gate. Two ticks with the same
     * signature produce a byte-identical pill, so we can skip the redraw. We fold
     * in the time-derived `stale` flag (Scanning→Idle must still flip when the
     * daemon goes quiet) and the pending hazardId (rising-edge force-expand), but
     * DROP updatedMs (it advances on every daemon write even with no visible
     * change) and the `expanded` user-toggle (that mutates views via its own path,
     * applyExpanded(), not render()). Also keyed on warnMode (chip state) + theme.
     */
    private fun renderSignature(state: OverlayState?, warnMode: String): String {
        if (state == null) return "null"
        val stale = System.currentTimeMillis() - state.updatedMs > STALE_MS
        // Recompute on a config/theme change too (themedCtx swap re-tints chips).
        return buildString {
            append(warnMode); append('|')
            append(stale); append('|')
            append(state.calLevel()); append('|')
            append(state.coverage); append('|')
            append(state.hazardAhead); append('|')
            append(state.nextHazardMeters); append('|')
            append(state.nextHazardRelBearingDeg); append('|')
            append(state.nextHazardSeverity); append('|')
            append(state.nextHazardType); append('|')
            append(state.zoneCount); append('|')
            append(state.zoneLengthM); append('|')
            append(state.zoneRough); append('|')
            append(state.pendingConfirm?.hazardId ?: "")
        }
    }

    /** Apply state to the views. MUST run on the main thread (view mutations).
     *  [state]/[warnMode] were read off the IO thread by the poll loop. */
    private fun render(state: OverlayState?, warnMode: String) {
        if (state == null) return
        // Staleness: if the daemon hasn't written recently, treat as no-data and
        // show the scanning/idle state rather than a frozen hazard.
        val stale = System.currentTimeMillis() - state.updatedMs > STALE_MS

        // Reflect the real warnMode on the quick-toggle chips every render (audit
        // UI #2: chips previously never showed actual state).
        applyWarnModeChips(warnMode)

        val calColor = when (state.calLevel()) {
            OverlayState.CalLevel.GREEN -> themeColor(R.color.status_success)
            OverlayState.CalLevel.ORANGE -> themeColor(R.color.status_warning)
            OverlayState.CalLevel.RED -> themeColor(R.color.status_danger)
        }
        pillCalDot?.setColorFilter(calColor, PorterDuff.Mode.SRC_IN)
        cardCalDot?.setColorFilter(calColor, PorterDuff.Mode.SRC_IN)
        cardCalLabel?.setText(
            when (state.calLevel()) {
                OverlayState.CalLevel.GREEN -> R.string.roadsense_cal_ready
                OverlayState.CalLevel.ORANGE -> R.string.roadsense_cal_partial
                OverlayState.CalLevel.RED -> R.string.roadsense_cal_learning
            }
        )

        if (state.hazardAhead && !stale) {
            // Zone-aware caption (D-032). A genuine washboard/rough STRETCH is one
            // continuous section you can't pass "one at a time", so it keeps the
            // "Rough section · 40 m" caption. A DISCRETE cluster (N separate bumps
            // within the 30 m zone gap) deliberately does NOT use a "3 bumps ahead"
            // count: the warn engine already drops each member as it's passed
            // (rank() filters range < minRangeM) and promotes the next-closest as the
            // new lead every tick, so the card walks the bumps closest→farthest one by
            // one — each showing its own type + live distance. Showing the lead's
            // identity (like a singleton) makes that sequence legible; a static count
            // hid which hazard was actually next and how far it was. The "there are
            // several" awareness now comes from the sequential reveal, not a number.
            val zoneCaption: String? = when {
                state.zoneRough -> localizedString(
                    R.string.roadsense_zone_rough,
                    state.zoneLengthM,
                )
                else -> null
            }
            pillLabel?.text = localizedString(
                R.string.roadsense_hazard_format,
                "${state.nextHazardMeters}${localizedString(R.string.roadsense_unit_meters)}",
                zoneCaption ?: severityLabel(state.nextHazardSeverity),
            )
            hazardDistance?.text = state.nextHazardMeters.toString()
            hazardSeverity?.text = zoneCaption ?: localizedString(
                R.string.roadsense_hazard_format,
                severityLabel(state.nextHazardSeverity),
                typeLabel(state.nextHazardType),
            )
            hazardArrow?.rotation = state.nextHazardRelBearingDeg.toFloat()
            val sevColor = severityColor(state.nextHazardSeverity)
            hazardArrow?.setColorFilter(sevColor, PorterDuff.Mode.SRC_IN)
            hazardArrow?.visibility = View.VISIBLE
            // Severity-tinted bloom behind the arrow — the dial glows the colour of
            // the threat. The pulse animation throbs its alpha for the beacon feel.
            hazardGlow?.setColorFilter(sevColor, PorterDuff.Mode.SRC_IN)
            hazardGlow?.visibility = View.VISIBLE
            // SOTA approach animation (R-OVL-2): pulse the arrow faster + harder as
            // the hazard closes. Map distance → pulse period (near = quick urgent
            // throb, far = slow gentle breathe). Severity-tinted glow comes from
            // the color filter above.
            startArrowPulse(state.nextHazardMeters, state.nextHazardSeverity)
        } else {
            // Two distinct "no hazard ahead" states — don't conflate them:
            //  • STALE (daemon not publishing): RoadSense isn't actively running —
            //    parked / ACC-off / not in DRIVING regime (onWarningTick early-returns
            //    so no fresh state arrives). Saying "Scanning" here is misleading; it
            //    isn't scanning anything. Show a neutral "Idle".
            //  • FRESH but nothing ahead: we ARE driving and the road is clear — that
            //    is genuinely "Scanning".
            if (stale) {
                pillLabel?.setText(R.string.roadsense_pill_idle)
                hazardSeverity?.setText(R.string.roadsense_status_idle)
            } else {
                pillLabel?.setText(R.string.roadsense_pill_scanning)
                // ROUTE-COVERAGE-aware idle caption (don't imply an unmapped road is
                // safe): "Road mapped · clear" only when we've surveyed this stretch
                // (coverage>=MAPPED=2), else "New road · learning" so the driver knows
                // a clear readout here just means "no data yet", not "confirmed clear".
                hazardSeverity?.setText(
                    if (state.coverage >= 2) R.string.roadsense_road_mapped
                    else R.string.roadsense_road_new
                )
            }
            hazardDistance?.setText(R.string.roadsense_dash_distance)
            hazardArrow?.setColorFilter(colorDim(), PorterDuff.Mode.SRC_IN)
            // Dim the dial bloom to a faint "idle" glow when nothing's ahead. The
            // idle alphas + arrow rotation reset are owned by stopArrowPulse() (audit
            // UI #6) so they live in one place rather than split across call sites.
            hazardGlow?.setColorFilter(colorDim(), PorterDuff.Mode.SRC_IN)
            stopArrowPulse()
        }

        // Calibration-Mode confirm card.
        val pending = if (stale) null else state.pendingConfirm
        if (pending != null) {
            confirmPanel?.visibility = View.VISIBLE
            confirmAssessment?.text = localizedString(
                R.string.roadsense_hazard_format,
                severityLabel(pending.algoSeverity),
                typeLabel(pending.algoType),
            )
            // Force-expand ONLY on the rising edge of a NEW pending id (audit UI #4:
            // forcing it open every 400 ms render fought the user's tap-to-collapse —
            // they could never dismiss it). After the first reveal, respect their choice.
            // On that same rising edge, PRE-FILL the correction selection to the algo
            // assessment (R-OVL-6: "the algorithm proposes"), so an immediate Confirm
            // accepts the algo's own type/severity unless the user adjusts.
            if (pending.hazardId != lastPendingId) {
                selectedSeverity = pending.algoSeverity
                selectedType = pending.algoType
                reflectConfirmSelection()
                if (!expanded) { expanded = true; applyExpanded() }
            }
            lastPendingId = pending.hazardId
        } else {
            confirmPanel?.visibility = View.GONE
            lastPendingId = null
        }
    }

    private fun readState(): OverlayState? = try {
        // mtime-gated loadConfig (NOT forceReload): the daemon (UID 2000) wrote this,
        // but loadConfig() stats the file's lastModified each call and only re-parses
        // when it actually changed — so we pick up daemon writes without doing a full
        // disk read + JSON parse on EVERY 400 ms poll. forceReload() here meant the
        // config was re-read+re-parsed 2-4×/s for the whole drive even while parked
        // and nothing changed (the "Config loaded from…" log spam). The daemon's
        // overlay-state writer changes the file ~every 3 s idle / ~500 ms during an
        // active approach, so loadConfig re-reads exactly when there's something new.
        // Worst case (ext4 1 s mtime granularity right after an app self-write) is a
        // single ~1 s-late render — invisible for a glanceable overlay, and the
        // audio warning is daemon-side and unaffected. The confirm-verdict round-trip
        // that DOES need cross-UID immediacy stays forceReload, daemon-side.
        val root = UnifiedConfigManager.loadConfig()
        OverlayState.fromJson(
            root.optJSONObject(OverlayState.SECTION)?.optJSONObject(OverlayState.KEY)
        )
    } catch (_: Throwable) { null }

    // ── Write-backs ──────────────────────────────────────────────────────────

    /**
     * Toggle the audio or visual channel of the SINGLE `warnMode` enum the daemon
     * actually reads ("visual" | "audio" | "both") — audit found the old code wrote
     * phantom `warnAudioEnabled`/`warnVisualEnabled` keys nothing consumed. Tapping
     * a chip flips whether that channel is present in the current mode; we never let
     * it reach "neither" (that's what the master warn toggle on the web page is for —
     * the last channel stays on). Reflects the new selection on both chips.
     */
    private fun toggleWarnMode(audioChip: Boolean) {
        // forceReload so we flip from the TRUE current mode, not a stale app-cache
        // value: the daemon rewrites the roadSense section on its overlay heartbeat,
        // so a plain (mtime-gated) loadConfig here could read a pre-daemon-write mode
        // and compute the wrong toggle. Runs on the IO thread (off the UI looper).
        val cur = try {
            UnifiedConfigManager.forceReload().optJSONObject("roadSense")
                ?.optString("warnMode", "both")?.lowercase() ?: "both"
        } catch (_: Throwable) { "both" }
        var audioOn = cur == "audio" || cur == "both"
        var visualOn = cur == "visual" || cur == "both"
        if (audioChip) audioOn = !audioOn else visualOn = !visualOn
        // Don't allow both-off; keep the channel the user just turned off's counterpart.
        if (!audioOn && !visualOn) { if (audioChip) visualOn = true else audioOn = true }
        val next = when {
            audioOn && visualOn -> "both"
            audioOn -> "audio"
            else -> "visual"
        }
        try {
            UnifiedConfigManager.updateSection("roadSense", org.json.JSONObject().put("warnMode", next))
        } catch (_: Throwable) {}
        // chip update is a view mutation → back to main
        handler.post { if (overlayView != null) applyWarnModeChips(next) }
    }

    /** Current warnMode from config (readState already refreshed the UCM cache
     *  this tick, so a plain loadConfig is cache-fresh — no extra disk read). */
    private fun currentWarnMode(): String = try {
        UnifiedConfigManager.loadConfig().optJSONObject("roadSense")
            ?.optString("warnMode", "both") ?: "both"
    } catch (_: Throwable) { "both" }

    /** Reflect a warnMode string on the two quick-toggle chips. */
    private fun applyWarnModeChips(mode: String) {
        val m = mode.lowercase()
        toggleAudio?.isSelected = (m == "audio" || m == "both")
        toggleVisual?.isSelected = (m == "visual" || m == "both")
    }

    private fun resolveConfirm(id: String?, confirmed: Boolean, severity: Int, type: Int) {
        // id + severity/type were captured on the main thread at click time (audit
        // UI #5) — no cross-thread read of the shared fields here.
        if (id == null) return
        // Hand the verdict to the daemon via a roadSense config write it polls. On a
        // confirm we include the (possibly corrected) severity/type so the daemon can
        // apply the user's adjustment (R-OVL-6); on a reject they're irrelevant.
        try {
            val verdict = org.json.JSONObject()
                .put("id", id)
                .put("confirmed", confirmed)
                .put("ts", System.currentTimeMillis())
            if (confirmed) {
                if (severity in 1..3) verdict.put("severity", severity)
                if (type >= 0) verdict.put("type", type)
            }
            UnifiedConfigManager.updateSection(
                "roadSense",
                org.json.JSONObject().put("pendingConfirmResult", verdict),
            )
        } catch (_: Throwable) {}
        // view mutation + the lastPendingId null-out → back to MAIN, the only thread
        // that otherwise reads/writes lastPendingId (render()), so the field stays
        // single-threaded (audit UI #5).
        handler.post {
            if (overlayView != null) confirmPanel?.visibility = View.GONE
            lastPendingId = null
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Dynamic strings must use the same locale-aware context as the inflated views. */
    private fun localizedString(resId: Int, vararg formatArgs: Any): String {
        val context = themedCtx ?: this
        return if (formatArgs.isEmpty()) {
            context.getString(resId)
        } else {
            context.getString(resId, *formatArgs)
        }
    }

    private fun severityLabel(sev: Int): String = localizedString(
        when (sev) { 1 -> R.string.roadsense_sev_minor; 2 -> R.string.roadsense_sev_moderate
            3 -> R.string.roadsense_sev_severe; else -> R.string.roadsense_clear_ahead }
    )

    private fun typeLabel(type: Int): String = localizedString(
        when (type) { 0 -> R.string.roadsense_type_breaker; 1 -> R.string.roadsense_type_pothole
            3 -> R.string.roadsense_type_rough; else -> R.string.roadsense_type_hazard }
    )

    private fun severityColor(sev: Int): Int = when (sev) {
        3 -> themeColor(R.color.status_danger)
        2 -> themeColor(R.color.status_warning)
        else -> themeColor(R.color.status_success)
    }

    /** Resolve a color from the day/night-themed context the overlay was inflated
     *  with, so status colors track the active theme (matches StatusOverlayService's
     *  getColor() use). Falls back to the service context if the themed one is null. */
    private fun themeColor(resId: Int): Int = (themedCtx ?: this).getColor(resId)

    /** Dim/idle tint for the arrow + glow when no hazard is ahead — a muted neutral
     *  pulled from the theme rather than a hardcoded translucent white. */
    private fun colorDim(): Int {
        val c = themeColor(R.color.text_muted)
        // Render at ~40% alpha so it reads as a faint idle marker, not a solid dot.
        return (c and 0x00FFFFFF) or (0x66 shl 24)
    }

    private fun themedContext(): Context {
        val cfg = Configuration(resources.configuration)
        var overridden = false

        // Day/night: a bare Service runs against the system configuration, so
        // AppCompatDelegate.setDefaultNightMode() doesn't reach the overlay unless
        // we mirror it onto the config here (matches StatusOverlayService).
        val mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
        when (mode) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> {
                cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        Configuration.UI_MODE_NIGHT_YES
                overridden = true
            }
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> {
                cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        Configuration.UI_MODE_NIGHT_NO
                overridden = true
            }
            else -> { /* follow-system — leave uiMode alone */ }
        }

        // Locale: the app's in-app language (AppCompatDelegate.setApplicationLocales,
        // set from LocaleManager in OverdriveApplication) is applied to Activities but
        // NOT to a bare Service — its Resources stay on the SYSTEM locale, so getString()
        // for the roadsense_* pill/caption strings would ignore the user's chosen
        // language. Pull the app locale list and set it on the config so the overlay
        // resolves the values-<lang>/ strings the rest of the app uses.
        val appLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        if (!appLocales.isEmpty) {
            val locale = appLocales[0]
            if (locale != null) {
                val locList = android.os.LocaleList(locale)
                cfg.setLocales(locList)
                overridden = true
            }
        }

        // No overrides (follow-system night + auto locale) → keep the service context.
        return if (overridden) createConfigurationContext(cfg) else this
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        // 3-tier + try-catch (StatusOverlayService pattern). SPECIAL_USE is an
        // API-34 type; passing it on 29-33 throws → onCreate dies before any log.
        // DATA_SYNC for 29-33, untyped below, plain-startForeground fallback so a
        // typed mismatch can never silently kill the service.
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(NOTIFICATION_ID, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground typed failed (${t.message}); falling back to untyped")
            try { startForeground(NOTIFICATION_ID, n) }
            catch (t2: Throwable) { Log.e(TAG, "startForeground untyped ALSO failed: ${t2.message}") }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, com.overdrive.app.util.AppLocaleContext.of(this).getString(R.string.notif_channel_roadsense_overlay), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("RoadSense")
            .setContentText(com.overdrive.app.util.AppLocaleContext.of(this).getString(R.string.notif_roadsense_overlay_text))
            .setSmallIcon(R.drawable.ic_roadsense)
            .setOngoing(true)
            .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
            .build()
    }

    companion object {
        /** Live instance while the overlay window is up, for the locale-change
         *  relocalize hook. @Volatile: written on the main thread (onCreate/onDestroy),
         *  read from the language-picker call site. */
        @Volatile private var instance: RoadSenseOverlayService? = null

        /** Re-inflate the running overlay in the newly-selected app language. No-op if
         *  the overlay isn't currently up. Call after AppCompatDelegate.setApplicationLocales. */
        fun relocalizeIfRunning() {
            instance?.relocalize()
        }

        private const val TAG = "RoadSense/Overlay"
        private const val CHANNEL = "roadsense_overlay"
        private const val NOTIFICATION_ID = 9986
        private const val PREFS = "roadsense_overlay"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"
        private const val DEFAULT_X = 24
        private const val DEFAULT_Y = 120
        private const val POLL_MS = 400L
        private const val STALE_MS = 4_000L
        private const val TOUCH_SLOP = 12
        /** Min change in the pulse period (ms) before we retarget the beacon's
         *  duration — avoids re-setting it every 400 ms poll for a sub-ms drift while
         *  still tracking the hazard closing. */
        private const val PULSE_PERIOD_RETARGET_MS = 60L
        /** Idle (no-hazard) arrow alpha — recedes the chevron so it reads inactive. */
        private const val IDLE_ARROW_ALPHA = 0.4f
        /** Idle dial-bloom alpha — a faint scanning glow when nothing's ahead. */
        private const val IDLE_GLOW_ALPHA = 0.25f
        // Status colors are NOT hardcoded here anymore — they're resolved from the
        // day/night theme via themeColor()/colorDim() (R.color.status_*), matching
        // StatusOverlayService so the overlay tracks the active theme.

        /** Start only if RoadSense wants the overlay and permission is granted.
         *  This is the APP-process path (MainActivity / DaemonKeepaliveService). */
        @JvmOverloads
        fun startIfPermitted(context: Context, knownShouldShow: Boolean? = null): Boolean {
            // Reuse a decision the caller already computed — syncWithConfig reads
            // the config, and re-reading here doubled a disk read + JSON parse.
            val shouldShow = knownShouldShow ?: try {
                RoadSenseConfig.snapshot(forceReload = true).overlayShouldShow()
            } catch (t: Throwable) {
                Log.w(TAG, "visibility config unavailable - not starting: ${t.message}")
                false
            }
            if (!shouldShow) {
                Log.i(TAG, "RoadSense disabled or overlay hidden - not starting")
                return false
            }
            if (!OverlayPermissionChecker.isGranted(context)) {
                Log.w(TAG, "no overlay permission — not starting")
                return false
            }
            context.startForegroundService(Intent(context, RoadSenseOverlayService::class.java))
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RoadSenseOverlayService::class.java))
        }

        /**
         * Reconcile the app-side service with the persisted master + visibility flags.
         * Native UI entry points use this so they cannot disagree about lifecycle.
         */
        fun syncWithConfig(context: Context): Boolean {
            val shouldShow = try {
                RoadSenseConfig.snapshot(forceReload = true).overlayShouldShow()
            } catch (t: Throwable) {
                Log.w(TAG, "visibility config unavailable - stopping: ${t.message}")
                false
            }
            return syncWithConfig(context, shouldShow)
        }

        /** Reconcile with a pre-computed decision, for callers that already read the
         *  config off the main thread (snapshot(forceReload) hits disk). */
        fun syncWithConfig(context: Context, shouldShow: Boolean): Boolean {
            return if (shouldShow) {
                startIfPermitted(context, shouldShow)
            } else {
                stop(context)
                false
            }
        }

        /** Fully-qualified component for the daemon's `am` launch. */
        private const val COMPONENT =
            "com.overdrive.app/com.overdrive.app.roadsense.overlay.RoadSenseOverlayService"

        /**
         * Start the overlay FROM THE DAEMON (app_process, shell uid). The daemon's
         * synthetic Context cannot startForegroundService() an app-process Service (a
         * silent cross-process no-op — the same constraint RoadSenseImuSidecarService
         * documents), so use the proven `am start-foreground-service` exec. The daemon
         * calls this on the ACC-on + feature-on transition (regime → DRIVING/RELAXED), so
         * the overlay appears as soon as the car is alive and the feature is enabled —
         * without the user opening MainActivity. The service self-guards the overlay
         * permission in onStartCommand, so this cleanly no-ops if it isn't granted.
         * Fire-and-forget (no waitFor) — runs on the daemon tick thread.
         */
        fun startFromDaemon() {
            execAm("am", "start-foreground-service", "-n", COMPONENT)
        }

        /** Stop the overlay FROM THE DAEMON (ACC-off / feature-off). */
        fun stopFromDaemon() {
            execAm("am", "stopservice", "-n", COMPONENT)
        }

        /** Serialized + waitFor so a stop and a following start can't reach
         *  ActivityManager out of order (fast master OFF→ON would kill the overlay). */
        private val amExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "roadsense-overlay-am").apply { isDaemon = true }
            }

        private fun execAm(vararg argv: String) {
            amExecutor.execute {
                var proc: Process? = null
                try {
                    // No shell: destroy() must target `am` itself, not a wrapper.
                    proc = Runtime.getRuntime().exec(argv)
                    // Bounded: an `am` blocked on a wedged system_server would
                    // otherwise park this single thread and stall every later call.
                    if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        Log.w(TAG, "exec timed out [${argv.joinToString(" ")}]")
                        proc.destroy()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "exec failed [${argv.joinToString(" ")}]: ${t.message}")
                } finally {
                    try { proc?.inputStream?.close() } catch (_: Throwable) {}
                    try { proc?.errorStream?.close() } catch (_: Throwable) {}
                    try { proc?.outputStream?.close() } catch (_: Throwable) {}
                }
            }
        }
    }
}
