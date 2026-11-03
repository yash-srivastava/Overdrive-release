package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Arrange mode: every widget over a dimmed screen, each with a glowing edit ring, a remove badge and,
 * on the corner facing the middle of the screen, a resize grip. A widget follows the finger and snaps
 * to the screen margins and centre lines and to the other widgets' edges, centres and spacing, with
 * guide lines showing what it snapped to; the grip or a pinch resizes it on its own, snapping the same
 * way. The bar's Add opens a tray of live previews of the widgets not on screen: tap one to add it,
 * drag it out to place it, or drop a widget on the tray to put it away. Nothing is kept until Done,
 * and the cross puts everything back. [start] null (coming from the row) starts them in rows.
 */
@SuppressLint("ViewConstructor")
class GlassArrangeLayer(
    context: Context,
    private val env: GlassEnv,
    private val ticker: GlassTicker,
    ids: List<String>,
    private val sizes: Map<String, Float>,
    start: Map<String, RectF>?,
    private val stackRects: (Map<String, SizeF>) -> Map<String, RectF>,
    private val onBind: () -> Unit,
    private val onDone: (shells: Map<String, RectF>, sizes: Map<String, Float>, ids: List<String>, reset: Boolean) -> Unit,
    private val onCancel: () -> Unit,
    /** Asks, once, for the saved and ready-made widgets, which arrive through [addCatalog]. */
    private val askCatalog: () -> Unit = {},
) : FrameLayout(context), GlassSurface {

    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    // on screen, in the widgets' order, with their glass in screen px
    private val views = LinkedHashMap<String, GlassWidgetView>()
    private val shells = LinkedHashMap<String, RectF>()
    // the tray's live previews, in the catalog's order
    private val previews = LinkedHashMap<String, GlassWidgetView>()
    // views playing their exit: laid out no more, released when it ends
    private val going = ArrayList<View>()
    // a widget put away keeps its size for when it comes back
    private val kept = HashMap<String, Float>()
    // the tray's order: the built-ins, then the saved and ready-made widgets once [addCatalog] brings them;
    // asked for when the tray first opens, and opening it once they arrive if it had nothing else to show
    private var order: List<String> = (GlassWidgets.IDS + ids).distinct()
    private var catalogAsked = false
    private var catalogRead = false
    private var openOnCatalog = false
    // a custom widget's name cut to its tile, once
    private val names = HashMap<String, String>()
    private var tray: GlassWidgetTray? = null
    private var staleNow = false
    private val bar = GlassArrangeBar(context, env, ticker) { action ->
        when (action) {
            GlassArrangeBar.Action.CANCEL -> leave { onCancel() }
            GlassArrangeBar.Action.ADD -> if (tray != null) closeTray() else openTray()
            GlassArrangeBar.Action.RESET -> leave { onDone(emptyMap(), emptyMap(), views.keys.toList(), true) }
            GlassArrangeBar.Action.DONE -> leave { onDone(shells.mapValues { RectF(it.value) }, views.mapValues { it.value.sizeK }, views.keys.toList(), false) }
        }
    }
    private var leaving = false
    // the status bar the window may lie under (Android 9/10 lay it out full screen): the bar sits below it
    private var insetTop = 0
    private var insetBottom = 0
    // what a navigation bar or a car's dock takes from the bottom, measured at layout
    private var bottomGap = 0
    private val vis = android.graphics.Rect()
    // Where the bar sits: 0 top centre, 1 bottom centre, 2..5 compact in the top-right, top-left,
    // bottom-right and bottom-left corners. It moves when a widget lands under it, and fades away
    // while one is held over it.
    private var barSlot = 0
    private val barAtBottom get() = barSlot == 1 || barSlot >= 4
    private var barAway = false
    private var docked = false
    // arrange mode always has a way out: untouched for a while, it cancels itself
    private val idle = Runnable { if (!leaving) leave { onCancel() } }

    // gestures
    private enum class G { NONE, DRAG, RESIZE, PINCH, BADGE, TILE, TRAY, EMPTY, SPENT }
    private var g = G.NONE
    private var gid: String? = null
    private var pointer = -1
    private var downX = 0f
    private var downY = 0f
    private var grabX = 0f
    private var grabY = 0f
    private var pressedBadge: String? = null
    private var barGesture = false
    // resize and pinch: the glass and size when it began, the size now, the fixed point, the grip's diagonal
    private val r0 = RectF()
    private var f0 = 1f
    private var fNow = 1f
    private var ax = 0f
    private var ay = 0f
    private var gdx = 0f
    private var gdy = 0f
    private var span0 = 1f
    private var focusX0 = 0f
    private var focusY0 = 0f
    // the two fingers of a pinch, by pointer id
    private var pinchA = -1
    private var pinchB = -1
    private var snapKey = ""
    private var atFull = false
    private var guideX = Float.NaN
    private var guideY = Float.NaN
    private val origin = IntArray(2)
    // the whole screen, which Done anchors each widget to (the window may leave out a navigation bar)
    private var screenW = 0f
    private var screenH = 0f

    // look
    private var scrimA = 0f
    private var pulse = 0f
    private var backdrop: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * resources.displayMetrics.density, 5f * resources.displayMetrics.density), 0f)
    }
    private val keyP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val chipText = GlassFonts.paint(context, 600, 15f * resources.displayMetrics.density, 0).apply { textAlign = Paint.Align.CENTER }
    private val caption = GlassFonts.paint(context, 600, 15f, 0).apply { textAlign = Paint.Align.CENTER }
    private val fm = Paint.FontMetrics()
    // each widget's badges fade out while it's held; the size readout shows while resizing
    private val marks = HashMap<String, Float>()
    private var chipA = 0f
    private var chipLabel = ""
    private var chipFull = false
    private val chipAt = RectF()
    private var lastStep = 0L
    private var released = false
    private val r = RectF()
    private val vr = RectF()
    private val pt = FloatArray(2)
    private val pulser = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        // every finger goes where the first went: one held on the bar can't press a widget's own keys
        isMotionEventSplittingEnabled = false
        val dm = realMetrics()
        screenW = dm.widthPixels.toFloat(); screenH = dm.heightPixels.toFloat()
        for (id in ids) views[id] = GlassWidgets.create(id, context, env, ticker).also { it.sizeK = sizeOf(id) }
        val from = start ?: stackRects(views.mapValues { SizeF(it.value.baseW * it.value.unitPx, it.value.baseH * it.value.unitPx) })
        for ((i, id) in ids.withIndex()) {
            val v = views.getValue(id)
            // widgets not on screen when arranging began (charging while not charging, say) go in free space
            val at = from[id] ?: freeSpot(v.baseW * v.unitPx, v.baseH * v.unitPx, shells.values + from.values,
                dm.widthPixels.toFloat(), dm.heightPixels.toFloat(), MARGIN * dp, GlassWidgetView.GAP * v.unitPx)
            shells[id] = RectF(at)
            addView(v, wrap())
            v.enter(80L + i * 50L)
        }
        bar.sizeK = barK(dm.widthPixels)
        addView(bar, wrap())
        refreshAdd()
        postDelayed(idle, IDLE_MS)
        bar.enter(0L)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L; interpolator = EASE
            addUpdateListener { scrimA = it.animatedValue as Float; invalidate() }
        }.start()
        if (!env.reduceMotion) pulser.start() else pulse = 0.5f
    }

    private fun wrap() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private fun realMetrics() = DisplayMetrics().also {
        @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(it)
    }

    private fun sizeOf(id: String) = (kept[id] ?: sizes[id] ?: 1f).coerceIn(MIN_SIZE, MAX_SIZE)

    // the bar keeps its own size whatever the widgets' scale, and shrinks only to fit the screen
    private fun barK(screenW: Int) = ((screenW - 2f * BAR_SIDE * dp) / (bar.baseW * dp)).coerceIn(0.5f, 1f) / env.scale

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
        @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
        if (top != insetTop || bottom != insetBottom) { insetTop = top; insetBottom = bottom; requestLayout() }
        return super.onApplyWindowInsets(insets)
    }

    // Back closes the tray, then cancels like the bar's cross
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP && !leaving) { if (tray != null) closeTray() else leave { onCancel() } }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    /** From outside (the Home key): cancel, as the bar's cross does. */
    fun cancel() { if (!leaving) leave { onCancel() } }

    // ── layout ───────────────────────────────────────────────────────────────

    // At the top, below the status bar and never closer than a car's own bar; at the bottom, above the
    // navigation bar and a car's dock, which draws over this window: Done must never end up under either.
    private fun barGlassTop(bottom: Boolean = barAtBottom): Float =
        if (!bottom) max(BAR_TOP_MIN * dp, BAR_TOP * dp + insetTop)
        else height - max(BAR_BOTTOM_MIN * dp, BAR_TOP * dp + bottomGap) - bar.baseH * bar.unitPx

    /** The bar's glass at [slot], in screen px. */
    private fun slotGlass(slot: Int): RectF {
        val kb = bar.unitPx
        val w = (if (slot >= 2) GlassArrangeBar.W_COMPACT else GlassArrangeBar.W) * kb
        val side = BAR_SIDE * dp
        val l = when (slot) { 2, 4 -> width - side - w; 3, 5 -> side; else -> (width - w) / 2f } + origin[0]
        val t = barGlassTop(slot == 1 || slot >= 4) + origin[1]
        return RectF(l, t, l + w, t + GlassArrangeBar.H * kb)
    }

    // the tray centres on the bar, as far as the screen allows
    private fun trayCentreX(w: Float): Float {
        val cx = slotGlass(barSlot).centerX() - origin[0]
        val side = TRAY_SIDE * dp
        return if (width < w + 2f * side) width / 2f else cx.coerceIn(side + w / 2f, width - side - w / 2f)
    }

    // the tray opens on the screen side of the bar: below it at the top, above it at the bottom
    private fun trayGlassTop(glassH: Float) =
        if (barAtBottom) barGlassTop() - TRAY_GAP * dp - glassH else barGlassTop() + bar.baseH * bar.unitPx + TRAY_GAP * dp

    // the height the tray's glass has on that side of the bar, in px
    private fun trayRoom(): Float =
        if (barAtBottom) barGlassTop() - TRAY_GAP * dp - max(MARGIN * dp, insetTop.toFloat())
        else height - trayGlassTop(0f) - max(MARGIN * dp, bottomGap.toFloat())

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        getLocationOnScreen(origin)
        if (changed || !docked) {
            getWindowVisibleDisplayFrame(vis)
            val byFrame = if (vis.height() > 0) origin[1] + height - vis.bottom else 0
            bottomGap = max(insetBottom, byFrame).coerceAtLeast(0)
        }
        // the first layout knows the screen: the bar starts where the widgets leave it room
        if (!docked) { docked = true; barSlot = chooseSlot(); bar.compact = barSlot >= 2 }
        val kb = bar.unitPx
        val bg = slotGlass(barSlot)
        val bl = (bg.left - origin[0] - GlassWidgetView.PAD_L * kb).roundToInt()
        val bt = (bg.top - origin[1] - GlassWidgetView.PAD_T * kb).roundToInt()
        bar.layout(bl, bt, bl + bar.measuredWidth, bt + bar.measuredHeight)
        for ((id, v) in views) {
            val s = shells.getValue(id)
            // one being resized stays laid out where it began; its size shows through its scale until it lets go
            if (id == gid && (g == G.RESIZE || g == G.PINCH)) { place(v, r0); continue }
            fitShell(v, s)
            place(v, s)
        }
        tray?.let { t ->
            val g = trayNow(t)
            val tk = t.unitPx
            val tl = (g.left - origin[0] - GlassWidgetView.PAD_L * tk).roundToInt()
            val tt = (g.top - origin[1] - GlassWidgetView.PAD_T * tk).roundToInt()
            t.layout(tl, tt, tl + t.measuredWidth, tt + t.measuredHeight)
        }
        for ((id, v) in previews) tileGlass(id, v)?.let { place(v, it) }
    }

    // a shell follows its widget's real glass (RoadSense shows as a pill when clear), keeping its top-left
    private fun fitShell(v: GlassWidgetView, s: RectF) {
        if (v.measuredWidth <= 0) return
        val k = v.unitPx
        s.right = s.left + v.measuredWidth - (GlassWidgetView.PAD_L + GlassWidgetView.PAD_R) * k
        s.bottom = s.top + v.measuredHeight - (GlassWidgetView.PAD_T + GlassWidgetView.PAD_B) * k
    }

    // shells are kept in screen px; a view is laid out at its glass minus the shadow margin (its
    // translation belongs to its motion, which would otherwise pull it back to the corner)
    private fun place(v: GlassWidgetView, s: RectF) {
        val k = v.unitPx
        val l = (s.left - origin[0] - GlassWidgetView.PAD_L * k).roundToInt()
        val t = (s.top - origin[1] - GlassWidgetView.PAD_T * k).roundToInt()
        v.layout(l, t, l + v.measuredWidth, t + v.measuredHeight)
    }

    /** The tray's glass at the size it's heading to, in screen px, centred beside the bar. */
    private fun trayTarget(t: GlassWidgetTray): RectF {
        val tk = t.unitPx
        val w = t.targetW * tk
        val cx = trayCentreX(w) + origin[0]
        val h = t.targetH * tk
        val top = trayGlassTop(h) + origin[1]
        return RectF(cx - w / 2f, top, cx + w / 2f, top + h)
    }

    // the tray's glass at the size it shows now (it morphs as tiles come and go), in screen px
    private fun trayNow(t: GlassWidgetTray): RectF {
        val tk = t.unitPx
        val gw = t.measuredWidth - (GlassWidgetView.PAD_L + GlassWidgetView.PAD_R) * tk
        val gh = t.measuredHeight - (GlassWidgetView.PAD_T + GlassWidgetView.PAD_B) * tk
        val l = trayCentreX(gw) - gw / 2f + origin[0]
        val top = trayGlassTop(gh) + origin[1]
        return RectF(l, top, l + gw, top + gh)
    }

    // a preview's glass centred in its tile (a RoadSense pill sits mid-tile), in screen px
    private fun tileGlass(id: String, v: GlassWidgetView): RectF? {
        val t = tray ?: return null
        val tile = t.tile(id) ?: return null
        val tk = t.unitPx
        val g = trayTarget(t)
        val cx = g.left + tile.centerX() * tk
        val cy = g.top + tile.centerY() * tk
        return RectF(cx - v.glassWPx / 2f, cy - v.glassHPx / 2f, cx + v.glassWPx / 2f, cy + v.glassHPx / 2f)
    }

    /** Where a view's glass is drawn now (its layout through its scale and translation), in screen px. */
    private fun visualGlass(v: GlassWidgetView, out: RectF): RectF {
        val k = v.unitPx
        val l = v.left + GlassWidgetView.PAD_L * k; val t = v.top + GlassWidgetView.PAD_T * k
        val rr = v.right - GlassWidgetView.PAD_R * k; val b = v.bottom - GlassWidgetView.PAD_B * k
        val px = v.left + v.pivotX; val py = v.top + v.pivotY
        val ox = origin[0] + v.translationX; val oy = origin[1] + v.translationY
        return out.apply { set(px + (l - px) * v.scaleX + ox, py + (t - py) * v.scaleY + oy, px + (rr - px) * v.scaleX + ox, py + (b - py) * v.scaleY + oy) }
    }

    private fun onScreen(s: RectF): RectF {
        val ox = origin[0].toFloat(); val oy = origin[1].toFloat()
        val dx = if (s.width() >= width || s.left < ox) ox - s.left else if (s.right > ox + width) ox + width - s.right else 0f
        val dy = if (s.height() >= height || s.top < oy) oy - s.top else if (s.bottom > oy + height) oy + height - s.bottom else 0f
        s.offset(dx, dy)
        return s
    }

    // ── motion ───────────────────────────────────────────────────────────────

    // drawn at [from] (its glass, screen px) and eased to [to], where it is laid out
    private fun glide(v: GlassWidgetView, from: RectF, to: RectF, ms: Long) {
        v.animate().cancel()
        val k = v.unitPx
        v.pivotX = GlassWidgetView.PAD_L * k; v.pivotY = GlassWidgetView.PAD_T * k
        val sc = if (to.width() > 1f) from.width() / to.width() else 1f
        v.scaleX = sc; v.scaleY = sc
        v.translationX = from.left - to.left; v.translationY = from.top - to.top
        v.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).alpha(1f)
            .setStartDelay(0L).setDuration(if (env.reduceMotion) 120L else ms).setInterpolator(EASE).start()
    }

    private fun settle(v: GlassWidgetView) {
        v.animate().cancel()
        v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f; v.translationY = 0f; v.alpha = 1f
    }

    // a widget caught mid-glide stays where it's drawn rather than jumping to where it was going
    private fun stick(id: String) {
        val v = views[id] ?: return
        val s = shells[id] ?: return
        if (v.translationX != 0f || v.translationY != 0f) onScreen(s.apply { offset(v.translationX, v.translationY) })
        settle(v)
        place(v, s)
    }

    private fun raise(v: GlassWidgetView, s: RectF, up: Boolean) {
        val k = v.unitPx
        v.pivotX = GlassWidgetView.PAD_L * k + s.width() / 2f
        v.pivotY = GlassWidgetView.PAD_T * k + s.height() / 2f
        v.animate().scaleX(if (up) LIFT else 1f).scaleY(if (up) LIFT else 1f).translationX(0f).translationY(0f).alpha(1f)
            .setStartDelay(0L).setDuration(if (up) 160L else 220L).setInterpolator(EASE).start()
    }

    private fun press(v: GlassWidgetView?, down: Boolean) {
        v ?: return
        val k = v.unitPx
        v.pivotX = GlassWidgetView.PAD_L * k + v.glassWPx / 2f
        v.pivotY = GlassWidgetView.PAD_T * k + v.glassHPx / 2f
        v.animate().scaleX(if (down) 0.94f else 1f).scaleY(if (down) 0.94f else 1f).translationX(0f).translationY(0f)
            .setStartDelay(0L).setDuration(if (down) 110L else 180L).setInterpolator(EASE).start()
    }

    private fun haptic(kind: Int) { performHapticFeedback(kind) }

    private fun discard(v: View) {
        if (!going.remove(v)) return
        removeView(v)
        (v as? GlassWidgetView)?.release()
    }

    // ── the bar keeps clear ──────────────────────────────────────────────────

    // how much of the widgets the bar (and a gap round it) would cover at [slot]
    private fun covered(slot: RectF): Float {
        val gap = GlassWidgetView.GAP * dp
        var sum = 0f
        for (s in shells.values) {
            val w = min(s.right, slot.right + gap) - max(s.left, slot.left - gap)
            val h = min(s.bottom, slot.bottom + gap) - max(s.top, slot.top - gap)
            if (w > 0f && h > 0f) sum += w * h
        }
        return sum
    }

    // Where the bar covers no widget: back at the top centre once that's free, else where it is, else
    // the bottom centre, else a corner in its compact form. On a full screen, wherever it covers least.
    private fun chooseSlot(): Int {
        if (covered(slotGlass(0)) <= 0f) return 0
        if (covered(slotGlass(barSlot)) <= 0f) return barSlot
        var best = barSlot
        var least = Float.MAX_VALUE
        for (i in 0..5) {
            val c = covered(slotGlass(i))
            if (c <= 0f) return i
            if (c < least) { least = c; best = i }
        }
        return best
    }

    /** The bar fades off its edge while a widget is held over it, so a widget can go right to the edge. */
    private fun duck(away: Boolean) {
        if (away == barAway) return
        barAway = away
        val dy = (if (barAtBottom) DUCK else -DUCK) * dp
        bar.animate().alpha(if (away) 0f else 1f).translationX(0f).translationY(if (away) dy else 0f).scaleX(1f).scaleY(1f)
            .setStartDelay(0L).setDuration(if (env.reduceMotion) 100L else 220L).setInterpolator(EASE).start()
    }

    // while a widget is held: away as soon as it comes near the bar
    private fun keepClear(s: RectF) {
        val slot = slotGlass(barSlot)
        val g = BAR_CLEAR * dp
        duck(s.right > slot.left - g && s.left < slot.right + g && s.bottom > slot.top - g && s.top < slot.bottom + g)
    }

    /** After a widget lands, the bar stays if it covers nothing, else moves to where it covers least. */
    private fun settleBar() {
        if (leaving || !docked) return
        val next = chooseSlot()
        if (next == barSlot) { duck(false); return }
        moveBar(next)
    }

    // the bar fades out where it was and in at [next], in the form that slot takes; the tray glides along
    private fun moveBar(next: Int) {
        val go = Runnable {
            if (leaving) return@Runnable
            val fromTray = tray?.let { visualGlass(it, RectF()) }
            val fromPreviews = previews.mapValues { visualGlass(it.value, RectF()) }
            barSlot = next
            barAway = false
            bar.compact = next >= 2
            bar.animate().cancel()
            bar.translationX = 0f
            bar.translationY = (if (barAtBottom) DUCK else -DUCK) * dp / 2f
            bar.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(0L)
                .setDuration(if (env.reduceMotion) 120L else 240L).setInterpolator(EASE).start()
            tray?.let { t -> if (fromTray != null) glide(t, fromTray, trayNow(t), FLY_MS) }
            for ((id, v) in previews) {
                val to = tileGlass(id, v) ?: continue
                glide(v, fromPreviews.getValue(id), to, FLY_MS)
            }
            requestLayout()
            invalidate()
        }
        if (bar.alpha > 0.05f && !env.reduceMotion) {
            bar.animate().alpha(0f).setStartDelay(0L).setDuration(110L).setInterpolator(EASE).withEndAction(go).start()
        } else go.run()
    }

    // ── gestures ─────────────────────────────────────────────────────────────

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        // the bar's glass handles its own buttons; everything else is this layer's, never a widget's tap
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            barGesture = onBar(e.x, e.y)
            removeCallbacks(idle); postDelayed(idle, IDLE_MS)
        }
        return !barGesture
    }

    private fun sx(e: MotionEvent, i: Int) = e.getX(i) + origin[0]
    private fun sy(e: MotionEvent, i: Int) = e.getY(i) + origin[1]
    private fun at(e: MotionEvent) = e.findPointerIndex(pointer).takeIf { it >= 0 } ?: 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (leaving) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(sx(e, 0), sy(e, 0), e.getPointerId(0))
            MotionEvent.ACTION_POINTER_DOWN -> when (g) {
                G.DRAG -> if (e.pointerCount == 2) startPinch(e)
                G.TILE -> { press(gid?.let { previews[it] }, false); g = G.SPENT }
                G.BADGE -> { pressedBadge = null; g = G.SPENT; invalidate() }
                else -> {}
            }
            MotionEvent.ACTION_MOVE -> {
                val i = at(e)
                val x = sx(e, i); val y = sy(e, i)
                when (g) {
                    G.DRAG -> drag(x, y)
                    G.RESIZE -> resize(x, y)
                    G.PINCH -> pinch(e)
                    G.TILE -> if (hypot(x - downX, y - downY) > slop) gid?.let { takeOut(it, x, y) }
                    G.BADGE -> if (pressedBadge != null && !nearBadge(gid, x, y)) { pressedBadge = null; invalidate() }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> when (g) {
                // only one of the pinching fingers, or the one on the grip, lifting ends it
                G.PINCH -> if (e.getPointerId(e.actionIndex).let { it == pinchA || it == pinchB }) { gid?.let { commit(it) }; g = G.SPENT; settleBar() }
                G.RESIZE -> if (e.getPointerId(e.actionIndex) == pointer) { gid?.let { commit(it) }; g = G.SPENT; settleBar() }
                G.DRAG -> if (e.getPointerId(e.actionIndex) == pointer) {
                    // the finger still down carries on the drag
                    val n = if (e.actionIndex == 0) 1 else 0
                    pointer = e.getPointerId(n)
                    gid?.let { shells[it] }?.let { s -> grabX = sx(e, n) - s.left; grabY = sy(e, n) - s.top }
                }
                else -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val i = at(e)
                up(sx(e, i), sy(e, i), e.actionMasked == MotionEvent.ACTION_CANCEL)
            }
        }
        return true
    }

    // whatever is drawn on top takes the touch: a widget lifted over the tray, its grip and badge, a
    // preview, the tray's glass, then the widgets beneath it
    private fun down(x: Float, y: Float, id0: Int) {
        downX = x; downY = y; pointer = id0; gid = null
        for (i in childCount - 1 downTo 0) {
            val v = getChildAt(i) as? GlassWidgetView ?: continue
            if (v === bar) continue
            if (v === tray) {
                if (!visualGlass(v, vr).contains(x, y)) continue
                g = G.TRAY
                return
            }
            val pid = previews.entries.firstOrNull { it.value === v }?.key
            if (pid != null) {
                if (!onTile(pid, v, x, y)) continue
                g = G.TILE; gid = pid
                press(v, true)
                return
            }
            val id = idOf(v) ?: continue
            when (markAt(id, v, x, y)) {
                true -> { startResize(id, x, y); return }
                false -> { g = G.BADGE; gid = id; pressedBadge = id; invalidate(); return }
                null -> {}
            }
            if (visualGlass(v, vr).contains(x, y)) { startDrag(id, x, y); return }
        }
        g = G.EMPTY
    }

    private fun up(x: Float, y: Float, cancelled: Boolean) {
        when (g) {
            G.DRAG -> drop(x, y, cancelled)
            G.RESIZE, G.PINCH -> gid?.let { commit(it) }
            G.TILE -> gid?.let { id -> press(previews[id], false); if (!cancelled) add(id) }
            G.BADGE -> { val id = gid; val hitIt = pressedBadge != null && pressedBadge == id; pressedBadge = null; if (!cancelled && hitIt && id != null) remove(id) }
            // a tap beside the tray closes it
            G.EMPTY -> if (!cancelled && tray != null && hypot(x - downX, y - downY) <= slop) closeTray()
            else -> {}
        }
        g = G.NONE; gid = null; pointer = -1
        guideX = Float.NaN; guideY = Float.NaN
        tray?.dropTarget = false
        settleBar()
        invalidate()
    }

    // the bar's glass only: its shadow margin lies over the screen, where widgets can be dragged
    private fun onBar(x: Float, y: Float): Boolean {
        val k = bar.unitPx
        val l = bar.left + bar.translationX + GlassWidgetView.PAD_L * k; val t = bar.top + bar.translationY + GlassWidgetView.PAD_T * k
        return bar.alpha > 0.05f && x >= l && x <= l + bar.glassWPx && y >= t && y <= t + bar.glassHPx
    }

    private fun idOf(v: View): String? = views.entries.firstOrNull { it.value === v }?.key

    // a preview, or the name under it
    private fun onTile(id: String, v: GlassWidgetView, x: Float, y: Float): Boolean {
        val t = tray ?: return false
        val tile = t.tile(id) ?: return false
        val tk = t.unitPx
        visualGlass(v, vr)
        val cx = vr.centerX(); val cy = vr.centerY()
        val hw = tile.width() * tk / 2f + 6f * dp; val hh = tile.height() * tk / 2f
        return x >= cx - hw && x <= cx + hw && y >= cy - hh - 6f * dp && y <= cy + hh + GlassWidgetTray.CAPTION * tk
    }

    // the widget's corner nearest the screen's is the one it keeps; its grip sits on the opposite corner,
    // facing the middle of the screen, and the remove badge on a top corner clear of the grip
    private fun anchoredRight(s: RectF) = s.centerX() > screenW / 2f
    private fun anchoredBottom(s: RectF) = s.centerY() > screenH / 2f
    private fun gripPoint(s: RectF, at: RectF, out: FloatArray) {
        out[0] = if (anchoredRight(s)) at.left else at.right
        out[1] = if (anchoredBottom(s)) at.top else at.bottom
    }
    private fun badgePoint(s: RectF, at: RectF, out: FloatArray) {
        out[0] = if (anchoredRight(s) && anchoredBottom(s)) at.right else at.left
        out[1] = at.top
    }

    /** [id]'s grip (true) or remove badge (false) under the point, null for neither. */
    private fun markAt(id: String, v: GlassWidgetView, x: Float, y: Float): Boolean? {
        if ((marks[id] ?: 0f) < 0.5f) return null
        val s = shells[id] ?: return null
        val reach = MARK_HIT * dp
        visualGlass(v, vr)
        gripPoint(s, vr, pt)
        if (hypot(x - pt[0], y - pt[1]) <= reach) return true
        if (views.size > 1) {
            badgePoint(s, vr, pt)
            if (hypot(x - pt[0], y - pt[1]) <= reach) return false
        }
        return null
    }

    private fun nearBadge(id: String?, x: Float, y: Float): Boolean {
        val v = id?.let { views[it] } ?: return false
        val s = shells[id] ?: return false
        badgePoint(s, visualGlass(v, vr), pt)
        return hypot(x - pt[0], y - pt[1]) <= MARK_HIT * dp * 1.4f
    }

    // ── moving ───────────────────────────────────────────────────────────────

    private fun startDrag(id: String, x: Float, y: Float) {
        val v = views[id] ?: return
        stick(id)
        val s = shells.getValue(id)
        g = G.DRAG; gid = id
        grabX = x - s.left; grabY = y - s.top
        bringChildToFront(v); bringChildToFront(bar)
        raise(v, s, true)
        invalidate()
    }

    private fun drag(x: Float, y: Float) {
        val id = gid ?: return
        val s = shells[id] ?: return
        val w = s.width(); val h = s.height()
        val p = snap(id, x - grabX, y - grabY, w, h)
        s.set(p[0], p[1], p[0] + w, p[1] + h)
        views[id]?.let { place(it, s) }
        keepClear(s)
        // held over the tray, it goes back in when let go (the last widget always stays)
        tray?.let { t -> t.dropTarget = views.size > 1 && visualGlass(t, vr).contains(x, y) }
        invalidate()
    }

    private fun drop(x: Float, y: Float, cancelled: Boolean) {
        val id = gid ?: return
        val t = tray
        if (!cancelled && t != null && views.size > 1 && visualGlass(t, vr).contains(x, y)) { putAway(id); return }
        views[id]?.let { raise(it, shells.getValue(id), false) }
    }

    /**
     * Snap [id]'s glass at ([l], [t]) to the nearest line within reach: screen margins and centre,
     * the other widgets' edges and centres, and their edges one gap away. Keeps it on screen.
     */
    private fun snap(id: String, l: Float, t: Float, w: Float, h: Float): FloatArray {
        val sw = width.toFloat(); val sh = height.toFloat()
        val ox = origin[0].toFloat(); val oy = origin[1].toFloat()
        val m = MARGIN * dp; val gap = GlassWidgetView.GAP * (views[id]?.unitPx ?: dp); val reach = REACH * dp
        val xs = ArrayList<FloatArray>(); val ys = ArrayList<FloatArray>()
        // (line, offset of the moving glass's edge from its left/top) pairs
        fun x(line: Float, edge: Float) { xs.add(floatArrayOf(line, edge)) }
        fun y(line: Float, edge: Float) { ys.add(floatArrayOf(line, edge)) }
        x(ox + m, 0f); x(ox + sw - m, w); x(ox + sw / 2f, w / 2f)
        y(oy + m, 0f); y(oy + sh - m, h); y(oy + sh / 2f, h / 2f)
        for ((other, o) in shells) {
            if (other == id) continue
            x(o.left, 0f); x(o.right, w); x(o.left, w); x(o.right, 0f); x(o.centerX(), w / 2f)
            x(o.right + gap, 0f); x(o.left - gap, w)
            y(o.top, 0f); y(o.bottom, h); y(o.top, h); y(o.bottom, 0f); y(o.centerY(), h / 2f)
            y(o.bottom + gap, 0f); y(o.top - gap, h)
        }
        var nl = l; var nt = t
        guideX = Float.NaN; guideY = Float.NaN
        var best = reach
        for (c in xs) { val d = abs(l + c[1] - c[0]); if (d < best) { best = d; nl = c[0] - c[1]; guideX = c[0] } }
        best = reach
        for (c in ys) { val d = abs(t + c[1] - c[0]); if (d < best) { best = d; nt = c[0] - c[1]; guideY = c[0] } }
        // a widget wider or taller than the screen pins to its top-left rather than throwing
        nl = nl.coerceIn(ox, max(ox, ox + sw - w)); nt = nt.coerceIn(oy, max(oy, oy + sh - h))
        return floatArrayOf(nl, nt)
    }

    // ── resizing ─────────────────────────────────────────────────────────────

    private fun startResize(id: String, x: Float, y: Float) {
        val v = views[id] ?: return
        stick(id)
        val s = shells.getValue(id)
        g = G.RESIZE; gid = id
        r0.set(s); f0 = v.sizeK; fNow = f0
        val right = anchoredRight(s); val bottom = anchoredBottom(s)
        ax = if (right) s.right else s.left; ay = if (bottom) s.bottom else s.top
        val gx = if (right) s.left else s.right; val gy = if (bottom) s.top else s.bottom
        gdx = gx - ax; gdy = gy - ay
        grabX = x - gx; grabY = y - gy
        // it grows and shrinks about the corner it keeps
        v.pivotX = ax - origin[0] - v.left; v.pivotY = ay - origin[1] - v.top
        snapKey = ""
        showChip(s)
        bringChildToFront(v); bringChildToFront(bar)
        invalidate()
    }

    private fun resize(x: Float, y: Float) {
        val id = gid ?: return
        val v = views[id] ?: return
        val len = gdx * gdx + gdy * gdy
        if (len < 1f) return
        val px = x - grabX; val py = y - grabY
        val lo = MIN_SIZE / f0
        var hi = MAX_SIZE / f0
        // the moving corner stays on screen
        val ox = origin[0].toFloat(); val oy = origin[1].toFloat()
        if (gdx > 0f) hi = min(hi, (ox + width - ax) / gdx) else if (gdx < 0f) hi = min(hi, (ax - ox) / -gdx)
        if (gdy > 0f) hi = min(hi, (oy + height - ay) / gdy) else if (gdy < 0f) hi = min(hi, (ay - oy) / -gdy)
        hi = max(hi, lo)
        val rel = snapSize(id, (((px - ax) * gdx + (py - ay) * gdy) / len).coerceIn(lo, hi), lo, hi)
        fNow = f0 * rel
        shells.getValue(id).set(ax + (r0.left - ax) * rel, ay + (r0.top - ay) * rel, ax + (r0.right - ax) * rel, ay + (r0.bottom - ay) * rel)
        v.scaleX = rel; v.scaleY = rel
        keepClear(shells.getValue(id))
        showChip(shells.getValue(id))
        invalidate()
    }

    /**
     * The moving corner snaps to the screen margins and to the other widgets' edges and the gap past
     * them, with a guide line; the widget's own normal size is a detent too.
     */
    private fun snapSize(id: String, rel: Float, lo: Float, hi: Float): Float {
        var best = REACH * dp
        var out = rel
        var key = ""
        var lineX = Float.NaN; var lineY = Float.NaN
        val cx = ax + gdx * rel; val cy = ay + gdy * rel
        fun x(line: Float, tag: String) {
            if (gdx == 0f) return
            val q = (line - ax) / gdx; val dd = abs(cx - line)
            if (q in lo..hi && dd < best) { best = dd; out = q; key = tag; lineX = line; lineY = Float.NaN }
        }
        fun y(line: Float, tag: String) {
            if (gdy == 0f) return
            val q = (line - ay) / gdy; val dd = abs(cy - line)
            if (q in lo..hi && dd < best) { best = dd; out = q; key = tag; lineY = line; lineX = Float.NaN }
        }
        val full = 1f / f0
        if (full in lo..hi) {
            val dd = abs(rel - full) * max(abs(gdx), abs(gdy))
            if (dd < best) { best = dd; out = full; key = "full" }
        }
        val ox = origin[0].toFloat(); val oy = origin[1].toFloat(); val m = MARGIN * dp
        x(ox + m, "m0"); x(ox + width - m, "m1"); y(oy + m, "m2"); y(oy + height - m, "m3")
        val gap = GlassWidgetView.GAP * (views[id]?.unitPx ?: dp)
        for ((other, o) in shells) {
            if (other == id) continue
            x(o.left, "l$other"); x(o.right, "r$other"); x(o.left - gap, "L$other"); x(o.right + gap, "R$other")
            y(o.top, "t$other"); y(o.bottom, "b$other"); y(o.top - gap, "T$other"); y(o.bottom + gap, "B$other")
        }
        guideX = lineX; guideY = lineY
        atFull = abs(f0 * out - 1f) < 0.004f
        if (key != snapKey) { if (key.isNotEmpty()) haptic(HapticFeedbackConstants.CLOCK_TICK); snapKey = key }
        return out
    }

    private fun span(e: MotionEvent): Float {
        val a = e.findPointerIndex(pinchA); val b = e.findPointerIndex(pinchB)
        return if (a < 0 || b < 0) span0 else hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b))
    }
    private fun focusX(e: MotionEvent): Float {
        val a = e.findPointerIndex(pinchA); val b = e.findPointerIndex(pinchB)
        return if (a < 0 || b < 0) focusX0 else (e.getX(a) + e.getX(b)) / 2f + origin[0]
    }
    private fun focusY(e: MotionEvent): Float {
        val a = e.findPointerIndex(pinchA); val b = e.findPointerIndex(pinchB)
        return if (a < 0 || b < 0) focusY0 else (e.getY(a) + e.getY(b)) / 2f + origin[1]
    }

    private fun startPinch(e: MotionEvent) {
        val id = gid ?: return
        val v = views[id] ?: return
        stick(id)
        val s = shells.getValue(id)
        g = G.PINCH
        r0.set(s); f0 = v.sizeK; fNow = f0
        pinchA = pointer; pinchB = e.getPointerId(e.actionIndex)
        span0 = max(1f, span(e)); focusX0 = focusX(e); focusY0 = focusY(e)
        ax = s.centerX(); ay = s.centerY()
        v.pivotX = ax - origin[0] - v.left; v.pivotY = ay - origin[1] - v.top
        snapKey = ""
        guideX = Float.NaN; guideY = Float.NaN
        showChip(s)
        invalidate()
    }

    // two fingers scale it about its centre and carry it along; the normal size is a detent
    private fun pinch(e: MotionEvent) {
        if (e.findPointerIndex(pinchA) < 0 || e.findPointerIndex(pinchB) < 0) return
        val id = gid ?: return
        val v = views[id] ?: return
        val lo = MIN_SIZE / f0
        val fit = min((width - 2f * MARGIN * dp) / max(1f, r0.width()), (height - 2f * MARGIN * dp) / max(1f, r0.height()))
        val hi = max(lo, min(MAX_SIZE / f0, fit))
        var rel = (span(e) / span0).coerceIn(lo, hi)
        val full = 1f / f0
        val near = full in lo..hi && abs(rel - full) * r0.width() < REACH * dp
        if (near) rel = full
        val key = if (near) "full" else ""
        if (key != snapKey) { if (near) haptic(HapticFeedbackConstants.CLOCK_TICK); snapKey = key }
        atFull = abs(f0 * rel - 1f) < 0.004f
        fNow = f0 * rel
        val dx = focusX(e) - focusX0; val dy = focusY(e) - focusY0
        shells.getValue(id).set(ax + (r0.left - ax) * rel + dx, ay + (r0.top - ay) * rel + dy, ax + (r0.right - ax) * rel + dx, ay + (r0.bottom - ay) * rel + dy)
        v.scaleX = rel; v.scaleY = rel; v.translationX = dx; v.translationY = dy
        keepClear(shells.getValue(id))
        showChip(shells.getValue(id))
        invalidate()
    }

    // the new size for real: measured and baked again where it was shown, then eased back on screen
    private fun commit(id: String) {
        val v = views[id] ?: return
        val shown = RectF(shells.getValue(id))
        // the grip's corner was kept on screen as it moved; a pinch can carry the widget off it
        val pinched = g == G.PINCH
        if (g == G.RESIZE || g == G.PINCH) g = G.SPENT
        settle(v)
        v.sizeK = fNow
        val to = if (pinched) onScreen(RectF(shown)) else RectF(shown)
        shells.getValue(id).set(to)
        if (abs(shown.left - to.left) > 0.5f || abs(shown.top - to.top) > 0.5f) glide(v, shown, to, 260L)
        guideX = Float.NaN; guideY = Float.NaN
        requestLayout()
        invalidate()
    }

    private fun showChip(s: RectF) {
        chipAt.set(s)
        chipLabel = "${(fNow * 100f).roundToInt()}%"
        chipFull = atFull
    }

    // ── adding and removing ──────────────────────────────────────────────────

    private fun hiddenIds() = order.filter { it !in views }

    // until the catalog is read Add stays ready: the app always ships ready-made widgets
    private fun refreshAdd() {
        bar.addState = when {
            tray != null -> GlassArrangeBar.AddState.OPEN
            hiddenIds().isEmpty() && catalogRead -> GlassArrangeBar.AddState.NONE
            else -> GlassArrangeBar.AddState.READY
        }
    }

    /**
     * The saved and ready-made widgets (their docs registered), read off the main thread: Add counts them,
     * and an open tray makes room for them as their previews enter.
     */
    fun addCatalog(ids: List<String>) {
        if (leaving) return
        catalogRead = true
        order = (GlassWidgets.IDS + ids + order).distinct()
        val t = tray
        // Add was tapped with only these to show: the tray opens now
        if (t == null) { if (openOnCatalog) { openOnCatalog = false; openTray() } }
        val more = if (t == null) emptyList() else hiddenIds().filter { it !in previews }
        if (t != null && more.isNotEmpty()) {
            val before = previews.mapValues { visualGlass(it.value, RectF()) }
            addPreviews(t, fitTray(t, more, env.reduceMotion))
            val sorted = LinkedHashMap<String, GlassWidgetView>()
            for (id in order) previews[id]?.let { sorted[id] = it }
            previews.clear(); previews.putAll(sorted)
            for ((id, v) in previews) {
                val from = before[id] ?: continue
                val to = tileGlass(id, v) ?: continue
                if (abs(from.left - to.left) > 0.5f || abs(from.top - to.top) > 0.5f) glide(v, from, to, FLY_MS)
            }
            onBind()
            requestLayout()
        }
        refreshAdd()
    }

    private fun openTray() {
        if (tray != null || leaving) return
        if (!catalogAsked) { catalogAsked = true; askCatalog() }
        val hidden = hiddenIds()
        if (hidden.isEmpty()) { if (!catalogRead) openOnCatalog = true; return }
        val t = GlassWidgetTray(context, env, ticker) { ((if (width > 0) width else realMetrics().widthPixels) - 2f * TRAY_SIDE * dp) / bar.unitPx }
        t.sizeK = bar.sizeK
        val shown = fitTray(t, hidden, true)
        tray = t
        addView(t, indexOfChild(bar), wrap())
        addPreviews(t, shown)
        t.enter(0L)
        refreshAdd()
        // the previews show live data, like the widgets on screen
        onBind()
        requestLayout()
    }

    /**
     * Lays [t] out for its previews and [more], in the catalog's order; saved and ready-made widgets of [more]
     * the screen has no room for are left out, the last first (a built-in never is). Returns what of [more] fits.
     */
    private fun fitTray(t: GlassWidgetTray, more: List<String>, instant: Boolean): List<String> {
        var keep = more
        val room = trayRoom()
        while (true) {
            t.setIds(if (previews.isEmpty()) keep else order.filter { it in previews || it in keep }, instant)
            val last = keep.lastOrNull() ?: return keep
            if (t.targetH * t.unitPx <= room || !GlassWidgets.isCustom(last) || (keep.size == 1 && previews.isEmpty())) return keep
            keep = keep.dropLast(1)
        }
    }

    private fun addPreviews(t: GlassWidgetTray, ids: List<String>) {
        for ((i, id) in ids.withIndex()) {
            val v = GlassWidgets.create(id, context, env, ticker)
            v.sizeK = t.sizeK * GlassWidgetTray.PREVIEW
            v.stale = staleNow
            previews[id] = v
            addView(v, indexOfChild(bar), wrap())
            v.enter(90L + i * 45L)
        }
    }

    private fun closeTray() {
        val t = tray ?: return
        tray = null
        t.dropTarget = false
        val out = ArrayList<View>(previews.values)
        out += t
        previews.clear()
        for (v in out) { going += v; (v as GlassWidgetView).exit() }
        postDelayed({ for (v in out) discard(v) }, GlassWidgetView.EXIT_MS + 40L)
        refreshAdd()
        invalidate()
    }

    // the previews still in the tray glide to where its new rows put them, as it morphs to fit
    private fun reflow() {
        val t = tray ?: return
        val before = previews.mapValues { visualGlass(it.value, RectF()) }
        t.setIds(previews.keys.toList(), env.reduceMotion)
        for ((id, v) in previews) {
            val to = tileGlass(id, v) ?: continue
            val from = before.getValue(id)
            if (abs(from.left - to.left) > 0.5f || abs(from.top - to.top) > 0.5f) glide(v, from, to, FLY_MS)
        }
    }

    /** A tapped preview flies to the first free space at its full size. */
    private fun add(id: String) {
        val v = previews[id] ?: return
        val from = visualGlass(v, RectF())
        settle(v)
        v.sizeK = sizeOf(id)
        kept.remove(id)
        val dm = realMetrics()
        // clear of the bar and the tray, even when the screen is full and it has to cover another widget
        val avoid = ArrayList<RectF>(2)
        avoid += visualGlass(bar, RectF())
        tray?.let { avoid += trayTarget(it) }
        val at = freeSpot(v.glassWPx, v.glassHPx, shells.values, dm.widthPixels.toFloat(), dm.heightPixels.toFloat(), MARGIN * dp, GlassWidgetView.GAP * v.unitPx, avoid)
        previews.remove(id)
        views[id] = v; shells[id] = at; marks[id] = 0f
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        reflow()
        bringChildToFront(v); bringChildToFront(bar)
        glide(v, from, at, FLY_MS)
        if (previews.isEmpty()) closeTray()
        refreshAdd()
        requestLayout()
        invalidate()
    }

    /** A preview dragged out of the tray grows to its full size under the finger and carries on as a drag. */
    private fun takeOut(id: String, x: Float, y: Float) {
        val v = previews[id] ?: return
        val from = visualGlass(v, RectF())
        val fx = ((x - from.left) / max(1f, from.width())).coerceIn(0f, 1f)
        val fy = ((y - from.top) / max(1f, from.height())).coerceIn(0f, 1f)
        settle(v)
        v.sizeK = sizeOf(id)
        kept.remove(id)
        val w = v.glassWPx; val h = v.glassHPx
        val at = onScreen(RectF(x - fx * w, y - fy * h, x - fx * w + w, y - fy * h + h))
        previews.remove(id)
        views[id] = v; shells[id] = at; marks[id] = 0f
        g = G.DRAG; gid = id
        grabX = x - at.left; grabY = y - at.top
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        reflow()
        bringChildToFront(v); bringChildToFront(bar)
        glide(v, from, at, 260L)
        if (previews.isEmpty()) closeTray()
        refreshAdd()
        requestLayout()
        invalidate()
    }

    /** The badge: with the tray open the widget goes into it; else it shrinks away into the bar's Add. */
    private fun remove(id: String) {
        if (views.size <= 1 || id !in views) return
        if (tray != null) { putAway(id); return }
        stick(id)
        val v = views.remove(id) ?: return
        val s = shells.remove(id) ?: return
        kept[id] = v.sizeK
        marks.remove(id)
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        val k = v.unitPx
        v.pivotX = GlassWidgetView.PAD_L * k + s.width() / 2f
        v.pivotY = GlassWidgetView.PAD_T * k + s.height() / 2f
        bar.addCentre(pt)
        val tx = bar.left + bar.translationX + pt[0] + origin[0]
        val ty = bar.top + bar.translationY + pt[1] + origin[1]
        going += v
        v.animate().scaleX(0.16f).scaleY(0.16f).alpha(0f).translationX(tx - s.centerX()).translationY(ty - s.centerY())
            .setStartDelay(0L).setDuration(if (env.reduceMotion) 150L else 380L).setInterpolator(AWAY)
            .withEndAction { discard(v) }.start()
        refreshAdd()
        invalidate()
    }

    /** Back into the open tray, at its place in the catalog's order. */
    private fun putAway(id: String) {
        val t = tray ?: return
        if (views.size <= 1) return
        val v = views[id] ?: return
        val from = visualGlass(v, RectF())
        views.remove(id); shells.remove(id); marks.remove(id)
        kept[id] = v.sizeK
        settle(v)
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        val before = previews.mapValues { visualGlass(it.value, RectF()) }
        val next = LinkedHashMap<String, GlassWidgetView>()
        for (pid in order) if (pid == id) next[pid] = v else previews[pid]?.let { next[pid] = it }
        previews.clear(); previews.putAll(next)
        v.sizeK = t.sizeK * GlassWidgetTray.PREVIEW
        t.setIds(previews.keys.toList(), env.reduceMotion)
        for ((pid, pv) in previews) {
            val to = tileGlass(pid, pv) ?: continue
            glide(pv, if (pid == id) from else before[pid] ?: to, to, FLY_MS)
        }
        bringChildToFront(v); bringChildToFront(bar)
        refreshAdd()
        requestLayout()
        invalidate()
    }

    // ── leaving ──────────────────────────────────────────────────────────────

    /** Closed from outside (driving off, a rotation): fade out and take no more input. */
    fun dismiss() = leave {}

    private fun leave(then: () -> Unit) {
        if (leaving) return
        // a resize under way ends at the size shown
        gid?.let { if (g == G.RESIZE || g == G.PINCH) commit(it) }
        g = G.SPENT
        leaving = true
        removeCallbacks(idle)
        pulser.cancel()
        for (v in views.values) v.exit()
        for (v in previews.values) v.exit()
        tray?.exit()
        bar.exit()
        ValueAnimator.ofFloat(scrimA, 0f).apply {
            duration = 220L
            addUpdateListener { scrimA = it.animatedValue as Float; invalidate() }
        }.start()
        then()
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(c: Canvas) {
        if (released || scrimA <= 0.01f) return
        if (backdrop?.width != width || backdrop?.height != height) bakeBackdrop()
        backdrop?.let { bitmapPaint.alpha = (scrimA * 255f).toInt(); c.drawBitmap(it, 0f, 0f, bitmapPaint) }
    }

    // the dimmed screen with a faint dot grid, baked once for the window size
    private fun bakeBackdrop() {
        backdrop?.recycle()
        if (width <= 0 || height <= 0) { backdrop = null; return }
        val t = env.theme
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (t.night) 0x9E000000.toInt() else 0x6B0B1A15
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = RadialGradient(width / 2f, height * 0.42f, maxOf(width, height) * 0.7f,
            intArrayOf(GlassColor.alpha(t.glowAccent, if (t.night) 0.07f else 0.05f), GlassColor.clear(t.glowAccent)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
        p.color = GlassColor.alpha(0xFFFFFFFF.toInt(), if (t.night) 0.07f else 0.16f)
        val step = GRID * dp
        var y = step / 2f
        while (y < height) {
            var x = step / 2f
            while (x < width) { c.drawCircle(x, y, 1.1f * dp, p); x += step }
            y += step
        }
        backdrop = bmp
    }

    // returns true while a badge or the size readout is still fading
    private fun stepMarks(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastStep == 0L) 0.016f else ((now - lastStep) / 1000f).coerceIn(0f, 0.05f)
        lastStep = now
        val held = g == G.DRAG || g == G.RESIZE || g == G.PINCH
        var moving = false
        fun ease(a: Float, want: Float): Float {
            if (env.reduceMotion) return want
            val n = a + (want - a) * min(1f, dt * 14f)
            return if (abs(n - want) < 0.01f) want else n
        }
        for (id in views.keys) {
            val want = if (leaving || (held && id == gid)) 0f else 1f
            val a = ease(marks[id] ?: 0f, want)
            marks[id] = a
            if (a != want) moving = true
        }
        chipA = ease(chipA, if (!leaving && (g == G.RESIZE || g == G.PINCH)) 1f else 0f)
        if (chipA > 0f && chipA < 1f) moving = true
        return moving
    }

    override fun dispatchDraw(c: Canvas) {
        val moving = stepMarks()
        super.dispatchDraw(c)
        if (scrimA > 0.01f) {
            // guides across the screen for whatever the moving widget snapped to
            val t = env.theme
            val ox = origin[0].toFloat(); val oy = origin[1].toFloat()
            guide.color = GlassColor.alpha(t.glowAccent, 0.85f * scrimA)
            guide.strokeWidth = 1.2f * dp
            if (!guideX.isNaN()) c.drawLine(guideX - ox, 0f, guideX - ox, height.toFloat(), guide)
            if (!guideY.isNaN()) c.drawLine(0f, guideY - oy, width.toFloat(), guideY - oy, guide)
            drawChip(c)
        }
        if (moving) postInvalidateOnAnimation()
    }

    // each widget's ring and badges right over it, so a widget or the tray on top covers them as it should
    override fun drawChild(c: Canvas, child: View, drawingTime: Long): Boolean {
        val drawn = super.drawChild(c, child, drawingTime)
        if (scrimA > 0.01f && child is GlassWidgetView) {
            val id = idOf(child)
            if (id != null) drawMarks(c, id, child) else if (child === tray) drawCaptions(c)
        }
        return drawn
    }

    private fun drawMarks(c: Canvas, id: String, v: GlassWidgetView) {
        val s = shells[id] ?: return
        val t = env.theme
        visualGlass(v, vr)
        vr.offset(-origin[0].toFloat(), -origin[1].toFloat())
        val lifted = id == gid && (g == G.DRAG || g == G.RESIZE || g == G.PINCH)
        val gap = RING_GAP * dp
        r.set(vr.left - gap, vr.top - gap, vr.right + gap, vr.bottom + gap)
        val rad = v.glassRadiusPx * v.scaleX + gap
        // edit rings: a soft pulse on each, brighter on the one in hand
        val wave = 0.5f + 0.5f * sin(pulse * 2.0 * Math.PI).toFloat()
        if (lifted) {
            ring.shader = null; ring.strokeWidth = 7f * dp
            ring.color = GlassColor.alpha(t.glowAccent, 0.18f * scrimA)
            c.drawRoundRect(r, rad, rad, ring)
        }
        ring.strokeWidth = 1.6f * dp
        ring.color = GlassColor.alpha(t.glowAccent, (if (lifted) 0.95f else 0.32f + 0.38f * wave) * scrimA * v.alpha)
        c.drawRoundRect(r, rad, rad, ring)
        val a = (marks[id] ?: 0f) * scrimA * v.alpha
        val resizing = id == gid && g == G.RESIZE
        if (resizing) {
            // the corner in hand, whichever side of the middle the growing widget reaches
            val rel = fNow / f0
            drawGrip(c, ax + gdx * rel - origin[0], ay + gdy * rel - origin[1], scrimA, true, gdx < 0f, gdy < 0f)
        } else if (a > 0.01f) {
            gripPoint(s, vr, pt)
            drawGrip(c, pt[0], pt[1], a, false, anchoredRight(s), anchoredBottom(s))
        }
        if (a > 0.01f && views.size > 1) {
            badgePoint(s, vr, pt)
            drawBadge(c, pt[0], pt[1], a, pressedBadge == id)
        }
    }

    // a small round glass key, lit like the bar's cross
    private fun drawKey(c: Canvas, cx: Float, cy: Float, a: Float, pressed: Boolean, lit: Int?) {
        val t = env.theme
        val rad = KEY_R * dp * (if (pressed) 0.9f else 1f)
        keyP.shader = null
        keyP.color = GlassColor.alpha(t.shadow, t.shadowA * 0.8f * a)
        c.drawCircle(cx + 0.6f * dp, cy + 1.6f * dp, rad + 0.8f * dp, keyP)
        keyP.color = GlassColor.alpha(if (t.night) 0xFF2B3532.toInt() else Color.WHITE, 0.97f * a)
        c.drawCircle(cx, cy, rad, keyP)
        if (lit != null) { keyP.color = GlassColor.alpha(lit, 0.24f * a); c.drawCircle(cx, cy, rad, keyP) }
        keyS.strokeWidth = 1f * dp
        keyS.color = GlassColor.alpha(if (t.night) Color.WHITE else 0xFF0B281E.toInt(), (if (t.night) 0.28f else 0.16f) * a)
        c.drawCircle(cx, cy, rad - 0.5f * dp, keyS)
    }

    private fun drawBadge(c: Canvas, cx: Float, cy: Float, a: Float, pressed: Boolean) {
        val t = env.theme
        drawKey(c, cx, cy, a, pressed, if (pressed) t.glowDanger else null)
        keyS.strokeWidth = 2.2f * dp
        keyS.color = GlassColor.alpha(t.text, a)
        val h = 5f * dp
        c.drawLine(cx - h, cy, cx + h, cy, keyS)
    }

    // a two-headed arrow along the diagonal it resizes on
    private fun drawGrip(c: Canvas, cx: Float, cy: Float, a: Float, active: Boolean, right: Boolean, bottom: Boolean) {
        val t = env.theme
        drawKey(c, cx, cy, a, false, if (active) t.glowAccent else null)
        val dx = if (right) -1f else 1f; val dy = if (bottom) -1f else 1f
        val h = 4.6f * dp; val head = 3.6f * dp
        keyS.strokeWidth = 1.9f * dp
        keyS.color = GlassColor.alpha(t.accent, a)
        val x1 = cx + dx * h; val y1 = cy + dy * h; val x0 = cx - dx * h; val y0 = cy - dy * h
        c.drawLine(x0, y0, x1, y1, keyS)
        c.drawLine(x1, y1, x1 - dx * head, y1, keyS); c.drawLine(x1, y1, x1, y1 - dy * head, keyS)
        c.drawLine(x0, y0, x0 + dx * head, y0, keyS); c.drawLine(x0, y0, x0, y0 + dy * head, keyS)
    }

    // the size while resizing, above the widget (below it when there's no room)
    private fun drawChip(c: Canvas) {
        if (chipA <= 0.01f) return
        val t = env.theme
        val a = chipA * scrimA
        chipText.getFontMetrics(fm)
        val w = chipText.measureText(chipLabel) + 26f * dp; val h = 30f * dp
        val cx = chipAt.centerX() - origin[0]
        var top = chipAt.top - origin[1] - 14f * dp - h
        if (top < 8f * dp) top = chipAt.bottom - origin[1] + 14f * dp
        r.set(cx - w / 2f, top, cx + w / 2f, top + h)
        keyP.shader = null
        keyP.color = GlassColor.alpha(t.shadow, t.shadowA * a)
        c.drawRoundRect(r.left + 1f * dp, r.top + 2f * dp, r.right + 1f * dp, r.bottom + 2f * dp, h / 2f, h / 2f, keyP)
        keyP.color = GlassColor.alpha(if (t.night) 0xFF2B3532.toInt() else Color.WHITE, 0.97f * a)
        c.drawRoundRect(r, h / 2f, h / 2f, keyP)
        chipText.color = GlassColor.alpha(if (chipFull) t.accent else t.text, a)
        c.drawText(chipLabel, cx, r.centerY() - (fm.ascent + fm.descent) / 2f, chipText)
    }

    // each preview's name under its tile, moving with it
    private fun drawCaptions(c: Canvas) {
        val t = tray ?: return
        val tk = t.unitPx
        val base = env.theme.text2
        for ((id, v) in previews) {
            if (v.width <= 0) continue
            val tile = t.tile(id) ?: continue
            visualGlass(v, vr)
            val x = vr.centerX() - origin[0]
            val y = vr.centerY() - origin[1] + tile.height() * tk / 2f + 20f * tk
            caption.color = base
            caption.alpha = (Color.alpha(base) * v.alpha * t.alpha).roundToInt().coerceIn(0, 255)
            c.save()
            c.translate(x, y)
            c.scale(tk, tk)
            c.drawText(nameOf(id, tile.width()), 0f, 0f, caption)
            c.restore()
        }
    }

    // a built-in's label as it is; a custom widget's name cut to its tile's width (design units) once
    private fun nameOf(id: String, w: Float): String {
        if (!GlassWidgets.isCustom(id)) return GlassWidgets.label(id)
        return names.getOrPut(id) {
            val full = GlassWidgets.label(id)
            if (caption.measureText(full) <= w) full
            else full.substring(0, caption.breakText(full, true, max(0f, w - caption.measureText("…")), null)).trimEnd() + "…"
        }
    }

    // ── surface ──────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    override fun <T : GlassWidgetView> widget(id: String): T? = (views[id] ?: previews[id]) as T?
    override fun show(ids: List<String>) {}
    override fun setHidden(hidden: Boolean) {}
    override fun setStale(stale: Boolean) {
        staleNow = stale
        for (v in views.values) v.stale = stale
        for (v in previews.values) v.stale = stale
    }
    override fun envChanged() {
        for (v in views.values) v.envChanged()
        for (v in previews.values) v.envChanged()
        tray?.envChanged()
        bar.envChanged()
    }
    override fun themeChanged() {
        for (v in views.values) v.themeChanged()
        for (v in previews.values) v.themeChanged()
        tray?.themeChanged()
        bar.themeChanged()
        backdrop?.recycle(); backdrop = null
        invalidate()
    }
    // a new overall size from the settings page: the widgets follow it, the bar and tray keep theirs
    override fun rescale() {
        bar.sizeK = barK(realMetrics().widthPixels)
        tray?.let { t ->
            t.sizeK = bar.sizeK
            for (v in previews.values) v.sizeK = t.sizeK * GlassWidgetTray.PREVIEW
            t.setIds(previews.keys.toList(), true)
        }
        for (v in views.values) v.requestLayout()
        requestLayout()
    }
    override fun invalidateWidgets() {
        for (v in views.values) v.invalidate()
        for (v in previews.values) v.invalidate()
    }
    override fun shells(): Map<String, RectF> = shells.mapValues { RectF(it.value) }

    override fun release() {
        released = true
        removeCallbacks(idle)
        pulser.cancel()
        for (v in views.values) v.release()
        for (v in previews.values) v.release()
        for (v in going) (v as? GlassWidgetView)?.release()
        going.clear()
        tray?.release()
        bar.release()
        backdrop?.recycle(); backdrop = null
        removeAllViews()
    }

    companion object {
        /** A widget's own size, against its normal one. */
        const val MIN_SIZE = 0.6f
        const val MAX_SIZE = 1.6f
        private const val LIFT = 1.035f
        /** Arrange mode left untouched this long cancels itself. */
        private const val IDLE_MS = 120_000L
        private const val MARGIN = 24f
        private const val REACH = 12f
        private const val GRID = 48f
        private const val RING_GAP = 5f
        private const val BAR_TOP = 18f
        private const val BAR_TOP_MIN = 72f
        /** Clears a car's dock along the bottom, which the system draws over overlays. */
        private const val BAR_BOTTOM_MIN = 110f
        // how near a held widget sends the bar away, and how far it slides
        private const val BAR_CLEAR = 20f
        private const val DUCK = 24f
        private const val BAR_SIDE = 16f
        private const val TRAY_GAP = 12f
        private const val TRAY_SIDE = 16f
        private const val KEY_R = 13f
        private const val MARK_HIT = 24f
        private const val FLY_MS = 420L
        private val EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        private val AWAY = PathInterpolator(0.4f, 0f, 0.7f, 0.4f)
    }
}

/**
 * Arrange mode's glass bar: cancel, a title with a hint, Add, Reset and Done. [compact] drops the title
 * and hint and turns Add into a round key, for a corner of a crowded screen. The keys are laid out from
 * the right edge, so both forms keep them in the same order.
 */
@SuppressLint("ViewConstructor")
class GlassArrangeBar(context: Context, env: GlassEnv, ticker: GlassTicker, private val onAction: (Action) -> Unit) :
    GlassWidgetView(context, env, ticker, W, H) {

    enum class Action { CANCEL, ADD, RESET, DONE }
    /** Add opens the tray; while it's open it closes it; with every widget on screen it rests dimmed. */
    enum class AddState { READY, OPEN, NONE }

    var addState = AddState.READY
        set(v) {
            if (field == v) return
            field = v
            plus.set(if (v == AddState.OPEN) 1f else 0f, env.reduceMotion)
            layersChanged()
            requestFrames()
        }

    var compact = false
        set(v) {
            if (field == v) return
            field = v
            setShellSize(if (v) W_COMPACT else W, H, false, true)
            layersChanged()
        }

    private val title = GlassFonts.paint(context, 600, 20f, 0)
    private val hint = GlassFonts.paint(context, 500, 14f, 0)
    private val label = GlassFonts.paint(context, 600, 16f, 0).apply { textAlign = Paint.Align.CENTER }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fm = Paint.FontMetrics()
    private val r = RectF()
    private val plus = Spring.number(0f)
    private var pressed: Action? = null
    private var down: Action? = null

    private val doneX get() = shellW - 16f - DONE_W
    private val resetX get() = doneX - 12f - RESET_W
    private val addW get() = if (compact) ADD_KEY else ADD_W
    private val addX get() = resetX - 12f - addW
    private val plusX get() = if (compact) addX + ADD_KEY / 2f else addX + PLUS_X

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = (e.x - shellLeftPx) / d; val y = (e.y - shellTopPx) / d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = hit(x, y); setPressed(down); return true }
            MotionEvent.ACTION_MOVE -> setPressed(if (hit(x, y) == down) down else null)
            MotionEvent.ACTION_UP -> {
                val a = down
                down = null; setPressed(null)
                if (a != null && hit(x, y) == a) { performClick(); onAction(a) }
            }
            MotionEvent.ACTION_CANCEL -> { down = null; setPressed(null) }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun setPressed(a: Action?) { if (a != pressed) { pressed = a; layersChanged() } }

    // generous: the whole height of the glass, and a little past each button's edge
    private fun hit(x: Float, y: Float): Action? = when {
        y < 0f || y > H -> null
        x >= 0f && x <= 78f -> Action.CANCEL
        x >= addX - 6f && x <= addX + addW + 6f -> if (addState == AddState.NONE) null else Action.ADD
        x >= resetX - 6f && x <= resetX + RESET_W + 6f -> Action.RESET
        x >= doneX - 6f && x <= shellW -> Action.DONE
        else -> null
    }

    /** The Add key's centre in this view's px, where a widget taken off shrinks into. */
    fun addCentre(out: FloatArray) {
        out[0] = shellLeftPx + plusX * d
        out[1] = shellTopPx + H / 2f * d
    }

    override fun animate(nowMs: Long, dt: Float): Boolean = plus.step(dt)

    // a round glass key, as the cross and the compact Add are drawn
    private fun roundKey(c: Canvas, t: GlassTheme, cx: Float) {
        p.shade(LinearGradient(cx - 22f, 20f, cx + 22f, 64f,
            if (t.night) intArrayOf(GlassColor.alpha(0xFFFFFFFF.toInt(), 0.13f), 0x33000000) else intArrayOf(0xFFFFFFFF.toInt(), 0xFFD9E1DC.toInt()), null, Shader.TileMode.CLAMP))
        c.drawCircle(cx, 42f, 22f, p)
        p.shader = null
        s.color = GlassColor.alpha(0xFFFFFFFF.toInt(), if (t.night) 0.35f else 0.9f); s.strokeWidth = 1f
        c.drawCircle(cx, 42f, 21.5f, s)
    }

    override fun drawBack(c: Canvas) {
        val t = env.theme
        // cancel: a round glass key with a cross
        val k0 = if (pressed == Action.CANCEL) 0.94f else 1f
        c.save(); c.scale(k0, k0, 42f, 42f)
        roundKey(c, t, 42f)
        s.color = t.text2; s.strokeWidth = 2.2f; s.strokeCap = Paint.Cap.ROUND
        c.drawLine(35f, 35f, 49f, 49f, s); c.drawLine(49f, 35f, 35f, 49f, s)
        s.strokeCap = Paint.Cap.BUTT
        c.restore()
        // title and hint
        if (!compact) {
            title.color = t.text
            c.drawText(gs(R.string.glass_arrange_title), 84f, 38f, title)
            hint.color = t.text2
            c.drawText(gs(R.string.glass_arrange_hint), 84f, 60f, hint)
        }
        label.getFontMetrics(fm)
        // add: an outlined pill (a round key when compact), lit while the tray is open, dimmed with nothing to add
        val k3 = if (pressed == Action.ADD) 0.95f else 1f
        val open = addState == AddState.OPEN
        val dim = addState == AddState.NONE
        r.set(addX, 20f, addX + addW, 64f)
        c.save(); c.scale(k3, k3, r.centerX(), r.centerY())
        if (dim) c.saveLayerAlpha(r.left - 2f, r.top - 2f, r.right + 2f, r.bottom + 2f, 102)
        if (compact) {
            roundKey(c, t, plusX)
            if (open) { s.color = GlassColor.alpha(t.glowAccent, 0.8f); s.strokeWidth = 1.4f; c.drawCircle(plusX, 42f, 21.3f, s) }
        } else {
            p.shader = null
            p.color = if (open) GlassColor.alpha(t.glowAccent, if (t.night) 0.2f else 0.16f) else if (t.night) GlassColor.alpha(t.glassBot, 0.4f) else 0x99FFFFFF.toInt()
            c.drawRoundRect(r, 22f, 22f, p)
            r.inset(0.6f, 0.6f)
            s.color = if (open) GlassColor.alpha(t.glowAccent, 0.8f) else if (t.night) 0x40FFFFFF else 0x400B281E; s.strokeWidth = 1.2f
            c.drawRoundRect(r, 21.4f, 21.4f, s)
            label.color = t.text
            c.drawText(gs(R.string.glass_arrange_add), addX + PLUS_X + 54f, 42f - (fm.ascent + fm.descent) / 2f, label)
        }
        if (dim) c.restore()
        c.restore()
        // reset: an outlined pill
        val k1 = if (pressed == Action.RESET) 0.95f else 1f
        r.set(resetX, 20f, resetX + RESET_W, 64f)
        c.save(); c.scale(k1, k1, r.centerX(), r.centerY())
        p.color = if (t.night) GlassColor.alpha(t.glassBot, 0.4f) else 0x99FFFFFF.toInt()
        c.drawRoundRect(r, 22f, 22f, p)
        r.inset(0.6f, 0.6f)
        s.color = if (t.night) 0x40FFFFFF else 0x400B281E; s.strokeWidth = 1.2f
        c.drawRoundRect(r, 21.4f, 21.4f, s)
        label.color = t.text
        c.drawText(gs(R.string.glass_arrange_reset), r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, label)
        c.restore()
        // done: a lit accent pill
        val k2 = if (pressed == Action.DONE) 0.95f else 1f
        r.set(doneX, 20f, doneX + DONE_W, 64f)
        c.save(); c.scale(k2, k2, r.centerX(), r.centerY())
        p.shade(LinearGradient(0f, 20f, 0f, 64f, GlassColor.lighten(t.glowAccent, 0.18f), GlassColor.darken(t.glowAccent, 0.12f), Shader.TileMode.CLAMP))
        c.drawRoundRect(r, 22f, 22f, p)
        p.shader = null
        p.color = GlassColor.alpha(0xFFFFFFFF.toInt(), 0.35f)
        c.drawRoundRect(r.left + 10f, r.top + 2f, r.right - 10f, r.top + 3.4f, 1f, 1f, p)
        label.color = if (t.night) 0xFF03140F.toInt() else 0xFFFFFFFF.toInt()
        c.drawText(gs(R.string.glass_arrange_done), r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, label)
        c.restore()
    }

    // Add's plus, which turns into a cross while the tray is open
    override fun drawLive(c: Canvas, nowMs: Long) {
        val t = env.theme
        val k3 = if (pressed == Action.ADD) 0.95f else 1f
        val cx = plusX; val cy = H / 2f
        c.save()
        c.scale(k3, k3, addX + addW / 2f, cy)
        c.rotate(45f * plus.value, cx, cy)
        s.strokeWidth = 2.2f; s.strokeCap = Paint.Cap.ROUND
        s.color = GlassColor.alpha(t.accent, if (addState == AddState.NONE) 0.4f else 1f)
        c.drawLine(cx - 6f, cy, cx + 6f, cy, s); c.drawLine(cx, cy - 6f, cx, cy + 6f, s)
        s.strokeCap = Paint.Cap.BUTT
        c.restore()
    }

    override fun drawFront(c: Canvas) {}

    companion object {
        const val W = 772f
        /** Cross, round Add key, Reset and Done. */
        const val W_COMPACT = 392f
        const val H = 84f
        private const val DONE_W = 112f
        private const val RESET_W = 96f
        private const val ADD_W = 136f
        private const val ADD_KEY = 56f
        private const val PLUS_X = 24f
    }
}
