package com.overdrive.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Editor + control overlay for the head-unit cluster-mirror PREVIEW pane.
 *
 * <h3>Coordinate model — CLUSTER SPACE (fixes the square-box regression)</h3>
 * The box the user drags is the preview rectangle the daemon mirror letterboxes the cluster into,
 * expressed IN CLUSTER PIXELS ({@code 0..clusterW × 0..clusterH}). It is drawn at the stage's
 * uniform scale ({@code viewW/clusterW}, {@code viewH/clusterH}). Storing it in cluster space is
 * what makes it ALWAYS panel-shaped by construction — the old model stored view px and separately
 * aspect-locked to a resolved panel size, and when that size resolved wrong (or disagreed with the
 * fixed-height stage) the box collapsed to a SQUARE. Here there is no separate aspect to fight.
 *
 * <p>The box is the cast app's FREEFORM BOUNDS on the cluster: on commit the fragment sends it to
 * the daemon, which resizes the cast app's task to that rect via IActivityTaskManager
 * (see ProjectionFragment.postClusterResize → ClusterCast.resize → ClusterFreeformWindow). The
 * head-unit mirror separately shows the WHOLE cluster (a see-and-touch view), so it is NOT driven
 * by this box — you see the resized app plus whatever surrounds it on the panel.
 *
 * <p>Because the box lives in cluster space, it is INDEPENDENT of the view size: a rotation /
 * responsive relayout just re-scales the drawing, with no remap and no drift.
 *
 * <h3>Modes</h3>
 *  - [Mode.ADJUST]: 8 resize handles + move; drag edits the preview rect (snapped to a grid + the
 *    panel's half/quarter guides). [onWindowChanged] fires live and once on release (commit=true).
 *    Optional [aspectLocked] keeps the box at the panel aspect.
 *  - [Mode.CONTROL]: taps/swipes ANYWHERE on the stage forward to the app (normalized 0..1 of the
 *    whole view = whole cluster, which the fragment maps through the full-cluster mirror to cluster px).
 *  - [Mode.IDLE]: the "Mirror preview" hint, no frame.
 *
 * Modeled on DashCast's {@code ResizeFrameView} (cluster-space frame, uniform scale), extended
 * with move, side handles, aspect lock, presets (driven from the fragment) and live control.
 */
class ProjectionBoundsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { IDLE, CONTROL, ADJUST }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            cancelActiveGesture()
            invalidate()
        }

    /** Cluster panel size the box is expressed in. Defaults to the Seal cluster; the fragment
     *  overrides it from the live daemon-reported panel size via [setClusterSize]. */
    private var clusterW = 1920
    private var clusterH = 720

    /** The app's window rectangle in CLUSTER px. Initialised to the full panel (fullscreen). */
    private var box = ProjectionBoundsGeometry.Rect(0, 0, clusterW, clusterH)
    private var boxInitialized = false

    /** Whether resize keeps the panel aspect. Off by default: DashCast-style free placement is
     *  the point of on-cluster positioning; the fragment can lock it via [setAspectLocked]. */
    private var aspectLocked = false

    /** Fires live while the box changes in ADJUST mode: (left,top,right,bottom in CLUSTER px,
     *  commit) — commit=false during a drag (throttled), true on release. */
    var onWindowChanged: ((l: Int, t: Int, r: Int, b: Int, commit: Boolean) -> Unit)? = null
    /** CONTROL-mode discrete tap, normalized (0..1) within the VIEW/stage (= whole cluster). */
    var onTap: ((nx: Double, ny: Double) -> Unit)? = null
    /** CONTROL-mode swipe, normalized (0..1) within the VIEW/stage, with duration ms. */
    var onSwipe: ((nx1: Double, ny1: Double, nx2: Double, ny2: Double, ms: Int) -> Unit)? = null

    // ── paints ──
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = ACCENT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(0x2E, 0x00, 0xD4, 0xAA)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.WHITE
    }
    private val handleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = ACCENT
    }
    private val scrim = Paint().apply { color = Color.argb(0x66, 0x00, 0x00, 0x00) }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xB0, 0xFF, 0xFF, 0xFF); textAlign = Paint.Align.CENTER
        textSize = dp(13f)
    }
    private val sizeLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(12f)
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    private val handleRadius = dp(9f)
    private val handleTouchRadius = dp(30f)
    private val gestureSlop = ViewConfiguration.get(context).scaledTouchSlop.toDouble()

    // ── interaction state ──
    private var activeHandle = ProjectionBoundsGeometry.HANDLE_NONE
    private var downBox = box
    private var adjustPointerId = MotionEvent.INVALID_POINTER_ID
    private var downViewX = 0f
    private var downViewY = 0f
    private var lastEmit = 0L

    // control-mode gesture
    private var controlTracking = false
    private var controlMultiTouch = false
    private var controlPointerId = MotionEvent.INVALID_POINTER_ID
    private var cDownX = 0f
    private var cDownY = 0f
    private var cDownT = 0L

    // ── public config ──

    /** Set the live cluster panel size and (re)fit the box. Called by the fragment from the
     *  daemon status poll. Preserves the box's NORMALIZED placement across a size change. */
    fun setClusterSize(w: Int, h: Int) {
        val nw = w.coerceAtLeast(1)
        val nh = h.coerceAtLeast(1)
        if (nw == clusterW && nh == clusterH) return
        // Re-express the current normalized box in the new cluster space so a panel-size refine
        // (e.g. dumpsys resolves the real size after the first poll) doesn't jump the window.
        if (boxInitialized) {
            val nl = box.left.toFloat() / clusterW
            val nt = box.top.toFloat() / clusterH
            val nr = box.right.toFloat() / clusterW
            val nb = box.bottom.toFloat() / clusterH
            box = ProjectionBoundsGeometry.clampToCluster(
                ProjectionBoundsGeometry.Rect(
                    (nl * nw).roundToInt(), (nt * nh).roundToInt(),
                    (nr * nw).roundToInt(), (nb * nh).roundToInt()
                ), nw, nh, aspectRatioOrNull()
            )
        }
        clusterW = nw
        clusterH = nh
        if (!boxInitialized) { box = ProjectionBoundsGeometry.preset(
            ProjectionBoundsGeometry.PRESET_FULL, nw, nh); boxInitialized = true }
        invalidate()
    }

    fun setAspectLocked(locked: Boolean) {
        if (aspectLocked == locked) return
        aspectLocked = locked
        if (locked && boxInitialized) {
            box = ProjectionBoundsGeometry.refitAspect(box, panelAspect(), clusterW, clusterH)
            invalidate()
            emitWindow(commit = true)
        }
    }

    /** Apply a named preset ([ProjectionBoundsGeometry.PRESET_*]) and commit it. */
    fun applyPreset(which: Int) {
        box = ProjectionBoundsGeometry.preset(which, clusterW, clusterH)
        boxInitialized = true
        invalidate()
        emitWindow(commit = true)
    }

    /** Current window rect in cluster px (copy-safe: Rect is immutable). */
    fun currentWindow(): ProjectionBoundsGeometry.Rect = box

    /** Restore a normalized (0..1 of the cluster) rect — the per-app persisted geometry. */
    fun setNormalizedWindow(nl: Float, nt: Float, nr: Float, nb: Float) {
        box = ProjectionBoundsGeometry.clampToCluster(
            ProjectionBoundsGeometry.Rect(
                (nl * clusterW).roundToInt(), (nt * clusterH).roundToInt(),
                (nr * clusterW).roundToInt(), (nb * clusterH).roundToInt()
            ), clusterW, clusterH, aspectRatioOrNull()
        )
        boxInitialized = true
        invalidate()
    }

    /** Current window as a normalized (0..1 of the cluster) rect, for persistence. */
    fun normalizedWindow(): FloatArray = floatArrayOf(
        box.left.toFloat() / clusterW, box.top.toFloat() / clusterH,
        box.right.toFloat() / clusterW, box.bottom.toFloat() / clusterH
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Box is in CLUSTER space, independent of view size — nothing to remap. Just repaint.
        if (!boxInitialized && clusterW > 0 && clusterH > 0) {
            box = ProjectionBoundsGeometry.preset(
                ProjectionBoundsGeometry.PRESET_FULL, clusterW, clusterH)
            boxInitialized = true
        }
        invalidate()
    }

    // ── drawing ──
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try { drawInternal(canvas) } catch (t: Throwable) {
            android.util.Log.e("ProjectionBoundsView", "onDraw failed", t)
        }
    }

    private fun drawInternal(canvas: Canvas) {
        if (!boxInitialized || width <= 0 || height <= 0) return
        val sx = width.toFloat() / clusterW
        val sy = height.toFloat() / clusterH
        val l = box.left * sx
        val t = box.top * sy
        val r = box.right * sx
        val b = box.bottom * sy
        when (mode) {
            Mode.IDLE -> {
                canvas.drawText(context.getString(
                    com.overdrive.app.R.string.projection_bounds_hint_idle),
                    (l + r) / 2f, (t + b) / 2f, hint)
            }
            Mode.CONTROL -> {
                // Transparent — the live mirror shows through; we only capture touches.
            }
            Mode.ADJUST -> {
                // Dim OUTSIDE the window rect to focus the frame (4 rects around the hole).
                canvas.drawRect(0f, 0f, width.toFloat(), t, scrim)
                canvas.drawRect(0f, b, width.toFloat(), height.toFloat(), scrim)
                canvas.drawRect(0f, t, l, b, scrim)
                canvas.drawRect(r, t, width.toFloat(), b, scrim)
                canvas.drawRect(l, t, r, b, fill)
                canvas.drawRect(l, t, r, b, outline)
                // 8 handles: 4 corners + 4 side-midpoints. Clamp each handle CENTER inward by its
                // radius so a box touching the stage edge still shows the whole handle (an
                // unclamped edge handle was drawn half-off-screen — part of the "can't grab it"
                // symptom). The clamp is visual only; the touch hit-test uses the same clamp.
                val hl = clampHandleX(l); val hr = clampHandleX(r)
                val ht = clampHandleY(t); val hb = clampHandleY(b)
                val hmx = (l + r) / 2f; val hmy = (t + b) / 2f
                drawHandle(canvas, hl, ht); drawHandle(canvas, hr, ht)
                drawHandle(canvas, hl, hb); drawHandle(canvas, hr, hb)
                drawHandle(canvas, hmx, ht); drawHandle(canvas, hmx, hb)
                drawHandle(canvas, hl, hmy); drawHandle(canvas, hr, hmy)
                // Size readout in cluster px, centred.
                canvas.drawText("${box.width} × ${box.height}", hmx, hmy, sizeLabel)
            }
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, handleRadius, handleFill)
        canvas.drawCircle(cx, cy, handleRadius, handleRim)
    }

    // ── touch ──
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return try {
            when (mode) {
                Mode.ADJUST -> handleAdjustTouch(event)
                Mode.CONTROL -> handleControlTouch(event)
                Mode.IDLE -> false
            }
        } catch (t: Throwable) {
            android.util.Log.e("ProjectionBoundsView", "onTouchEvent failed", t)
            false
        }
    }

    private fun handleAdjustTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                // In ADJUST mode the overlay OWNS every touch on the stage: claim the gesture
                // immediately (so the enclosing NestedScrollView can never steal a resize/move as
                // a scroll — the "sometimes works, sometimes not" bug) and pick the nearest
                // handle, falling back to MOVE. hitTest never returns NONE in adjust now, so a
                // press anywhere is actionable and we never return false here.
                activeHandle = hitTest(x, y)
                adjustPointerId = event.getPointerId(index)
                downBox = box
                downViewX = x
                downViewY = y
                lastEmit = 0L
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (adjustPointerId == MotionEvent.INVALID_POINTER_ID) return false
                val index = event.findPointerIndex(adjustPointerId)
                if (index < 0) { finishAdjustGesture(); return true }
                updateAdjustGesture(event.getX(index), event.getY(index), live = true)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (adjustPointerId == MotionEvent.INVALID_POINTER_ID) return false
                if (event.getPointerId(event.actionIndex) == adjustPointerId) {
                    updateAdjustGesture(event.getX(event.actionIndex),
                        event.getY(event.actionIndex), live = false)
                    finishAdjustGesture()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (adjustPointerId != MotionEvent.INVALID_POINTER_ID) {
                    val index = event.findPointerIndex(adjustPointerId)
                    if (index >= 0) updateAdjustGesture(event.getX(index), event.getY(index), live = false)
                    finishAdjustGesture()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (adjustPointerId == MotionEvent.INVALID_POINTER_ID) return false
                finishAdjustGesture()
                return true
            }
        }
        return adjustPointerId != MotionEvent.INVALID_POINTER_ID
    }

    private fun updateAdjustGesture(x: Float, y: Float, live: Boolean) {
        val sx = width.toFloat() / clusterW
        val sy = height.toFloat() / clusterH
        if (sx <= 0f || sy <= 0f) return
        // View px delta → cluster px delta.
        val dx = ((x - downViewX) / sx).roundToInt()
        val dy = ((y - downViewY) / sy).roundToInt()
        box = ProjectionBoundsGeometry.applyDrag(
            activeHandle, downBox, dx, dy, clusterW, clusterH, aspectRatioOrNull())
        invalidate()
        if (live) emitThrottled() else emitWindow(commit = false)
    }

    private fun finishAdjustGesture() {
        val changed = activeHandle != ProjectionBoundsGeometry.HANDLE_NONE
        activeHandle = ProjectionBoundsGeometry.HANDLE_NONE
        adjustPointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        if (changed) emitWindow(commit = true)
    }

    private fun handleControlTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                // The mirror now shows the WHOLE cluster, so CONTROL captures touches ANYWHERE on
                // the stage (not just a preview box) and forwards them to the cluster app.
                controlTracking = true
                controlMultiTouch = false
                controlPointerId = event.getPointerId(index)
                cDownX = event.getX(index)
                cDownY = event.getY(index)
                cDownT = event.eventTime
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!controlTracking) return false
                controlMultiTouch = true   // relay is single-pointer; ignore pinches
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!controlTracking) return false
                if (event.findPointerIndex(controlPointerId) < 0) resetControlGesture()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (!controlTracking) return false
                if (event.getPointerId(event.actionIndex) == controlPointerId) controlMultiTouch = true
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!controlTracking) return false
                resetControlGesture()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!controlTracking) return false
                val index = event.findPointerIndex(controlPointerId)
                if (index < 0 || controlMultiTouch) { resetControlGesture(); return true }
                val upX = event.getX(index)
                val upY = event.getY(index)
                // Normalize to the WHOLE VIEW (= whole cluster): the mirror shows the full cluster,
                // so a fraction of the view is the same fraction of the cluster. The fragment maps
                // these to full-surface px and the relay inverts them to cluster px.
                val vw = width.toFloat().coerceAtLeast(1f)
                val vh = height.toFloat().coerceAtLeast(1f)
                val nx1 = (cDownX / vw).toDouble().coerceIn(0.0, 1.0)
                val ny1 = (cDownY / vh).toDouble().coerceIn(0.0, 1.0)
                val nx2 = (upX / vw).toDouble().coerceIn(0.0, 1.0)
                val ny2 = (upY / vh).toDouble().coerceIn(0.0, 1.0)
                val dist = hypot((upX - cDownX).toDouble(), (upY - cDownY).toDouble())
                val elapsed = (event.eventTime - cDownT).coerceAtLeast(0L)
                val duration = elapsed.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS).toInt()
                resetControlGesture()
                if (dist > gestureSlop || elapsed >= ViewConfiguration.getLongPressTimeout().toLong()) {
                    onSwipe?.invoke(nx1, ny1, nx2, ny2, duration)
                } else {
                    onTap?.invoke(nx1, ny1)
                    performClick()
                }
                return true
            }
        }
        return controlTracking
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /** Which handle (if any) the point grabs. Corners+sides tested first (in view px), then the
     *  interior counts as MOVE. Mirrors DashCast ResizeFrameView.hitTest. */
    private fun hitTest(x: Float, y: Float): Int {
        val sx = width.toFloat() / clusterW
        val sy = height.toFloat() / clusterH
        val l = box.left * sx; val t = box.top * sy
        val r = box.right * sx; val b = box.bottom * sy
        // Test against the CLAMPED handle centers (matching where they're drawn), so an edge
        // handle is grabbable even when the box touches the stage boundary.
        val hl = clampHandleX(l); val hr = clampHandleX(r)
        val ht = clampHandleY(t); val hb = clampHandleY(b)
        val mx = (l + r) / 2f; val my = (t + b) / 2f
        var best = ProjectionBoundsGeometry.HANDLE_NONE
        var bestD = handleTouchRadius.toDouble()
        val candidates = arrayOf(
            Triple(ProjectionBoundsGeometry.HANDLE_TL, hl, ht),
            Triple(ProjectionBoundsGeometry.HANDLE_TR, hr, ht),
            Triple(ProjectionBoundsGeometry.HANDLE_BL, hl, hb),
            Triple(ProjectionBoundsGeometry.HANDLE_BR, hr, hb),
            Triple(ProjectionBoundsGeometry.HANDLE_T, mx, ht),
            Triple(ProjectionBoundsGeometry.HANDLE_B, mx, hb),
            Triple(ProjectionBoundsGeometry.HANDLE_L, hl, my),
            Triple(ProjectionBoundsGeometry.HANDLE_R, hr, my)
        )
        for ((id, hx, hy) in candidates) {
            val d = hypot((x - hx).toDouble(), (y - hy).toDouble())
            if (d <= bestD) { bestD = d; best = id }
        }
        if (best != ProjectionBoundsGeometry.HANDLE_NONE) return best
        // Inside the box (with a little slop) → MOVE.
        val slop = handleRadius
        if (x >= l - slop && x <= r + slop && y >= t - slop && y <= b + slop) {
            return ProjectionBoundsGeometry.HANDLE_MOVE
        }
        // Outside the box entirely (in ADJUST the overlay owns the whole stage): grab the NEAREST
        // corner so a press in the dimmed margin still starts a resize instead of doing nothing.
        var nearestCorner = ProjectionBoundsGeometry.HANDLE_BR
        var nd = Double.POSITIVE_INFINITY
        val corners = arrayOf(
            Triple(ProjectionBoundsGeometry.HANDLE_TL, hl, ht),
            Triple(ProjectionBoundsGeometry.HANDLE_TR, hr, ht),
            Triple(ProjectionBoundsGeometry.HANDLE_BL, hl, hb),
            Triple(ProjectionBoundsGeometry.HANDLE_BR, hr, hb)
        )
        for ((id, hx, hy) in corners) {
            val d = hypot((x - hx).toDouble(), (y - hy).toDouble())
            if (d < nd) { nd = d; nearestCorner = id }
        }
        return nearestCorner
    }

    /** Clamp a handle's drawn X center so it stays fully inside the view even when the box edge
     *  is at the stage boundary (keeps edge handles on-screen + grabbable). */
    private fun clampHandleX(x: Float): Float =
        x.coerceIn(handleRadius, (width - handleRadius).coerceAtLeast(handleRadius))

    private fun clampHandleY(y: Float): Float =
        y.coerceIn(handleRadius, (height - handleRadius).coerceAtLeast(handleRadius))

    private fun emitThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastEmit < EMIT_THROTTLE_MS) return
        lastEmit = now
        emitWindow(commit = false)
    }

    private fun emitWindow(commit: Boolean) {
        onWindowChanged?.invoke(box.left, box.top, box.right, box.bottom, commit)
    }

    private fun resetControlGesture() {
        controlTracking = false
        controlMultiTouch = false
        controlPointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun cancelActiveGesture() {
        activeHandle = ProjectionBoundsGeometry.HANDLE_NONE
        adjustPointerId = MotionEvent.INVALID_POINTER_ID
        resetControlGesture()
    }

    override fun onDetachedFromWindow() {
        cancelActiveGesture()
        super.onDetachedFromWindow()
    }

    private fun panelAspect(): Float = clusterW.toFloat() / clusterH.coerceAtLeast(1).toFloat()
    private fun aspectRatioOrNull(): Float? = if (aspectLocked) panelAspect() else null
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val MIN_GESTURE_MS = 50L
        private const val MAX_GESTURE_MS = 3000L
        private val ACCENT = Color.rgb(0x00, 0xD4, 0xAA)
        private const val EMIT_THROTTLE_MS = 60L   // ~16fps live window updates (daemon resize is heavier than a layer move)
    }
}
