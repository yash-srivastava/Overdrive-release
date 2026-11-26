package com.overdrive.app.overlay.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arranged layout: every widget in its own small overlay window, where the user put it. A
 * window hangs from the screen corner nearest its widget, so a widget that changes shape grows
 * away from the edges it sits against, and it only blocks touches over its own widget.
 */
class GlassFreeLayout(
    private val context: Context,
    private val wm: WindowManager,
    private val env: GlassEnv,
    private val ticker: GlassTicker,
    private val onArrange: () -> Unit,
) : GlassSurface {

    /** The glass's anchored corner as fractions of the screen; [right]/[bottom] pick which corner; [size] its own size. */
    data class Spot(val x: Float, val y: Float, val right: Boolean, val bottom: Boolean, val size: Float = 1f)

    private val frames = LinkedHashMap<String, Frame>()
    private val leaving = ArrayList<Frame>()
    // Android 9/10 place overlays inside the navigation bar's frame, which grows and shrinks as apps
    // hide the bar; every couple of seconds each window is checked against its spot again
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recheck = object : Runnable {
        override fun run() {
            if (!hiddenNow) for (f in frames.values) if (f.attached) f.recheck()
            handler.postDelayed(this, RECHECK_MS)
        }
    }
    private var spots: Map<String, Spot> = emptyMap()
    // places for widgets added after the arrangement was saved, kept until it's saved again
    private val extra = HashMap<String, Spot>()
    private var shown: List<String> = emptyList()
    private var hiddenNow = false
    private var staleNow = false
    private val loc = IntArray(2)

    /** One widget's window: packs the widget against its anchored corner and keeps room while it morphs. */
    @SuppressLint("ViewConstructor")
    private inner class Frame(val id: String, val view: GlassWidgetView) : FrameLayout(context) {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "GlassWidget:$id"
            // placed in whole-screen px: a status or navigation bar that comes and goes must not move it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        var attached = false
        private val press = ArrangePress(this, onArrange)
        // a touch on this window's shadow margin that lands on a neighbour's glass goes to that neighbour
        private var forwardTo: Frame? = null
        private val here = IntArray(2)
        private val there = IntArray(2)
        val hideWindow = Runnable { if (hiddenNow) visibility = INVISIBLE }
        // the corner the frame was placed from, kept to correct a firmware that offsets overlay windows
        var targetX = Float.NaN
        var targetY = Float.NaN
        var corrected = false
        // the window offsets place() computed: corrections stay within reach of them (a firmware that
        // clamps a window would otherwise be pushed further on every re-check)
        var placedX = 0
        var placedY = 0

        // only the glass takes touches; the shadow margin passes them to whatever is beneath
        private val touchRegion = GlassTouchRegion(this) { r ->
            val k = view.unitPx
            r.set((view.left + GlassWidgetView.PAD_L * k).toInt(), (view.top + GlassWidgetView.PAD_T * k).toInt(),
                (view.right - GlassWidgetView.PAD_R * k).toInt(), (view.bottom - GlassWidgetView.PAD_B * k).toInt())
        }

        init {
            clipChildren = false; clipToPadding = false
            addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            touchRegion.attach()
        }

        override fun onDetachedFromWindow() {
            touchRegion.detach()
            super.onDetachedFromWindow()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (!view.morphing) return
            setMeasuredDimension(maxOf(measuredWidth, view.reserveWidthPx().toInt()), maxOf(measuredHeight, view.reserveHeightPx().toInt()))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            correct()
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) forwardTo = if (onGlass(e.x, e.y)) null else frameUnder(this, e.rawX, e.rawY)
            val to = forwardTo ?: return super.dispatchTouchEvent(e)
            getLocationOnScreen(here); to.getLocationOnScreen(there)
            val ev = MotionEvent.obtain(e)
            ev.offsetLocation((here[0] - there[0]).toFloat(), (here[1] - there[1]).toFloat())
            val handled = to.dispatchTouchEvent(ev)
            ev.recycle()
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) forwardTo = null
            return handled
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean = press.onEvent(e, onGlass(e.x, e.y)) || super.onInterceptTouchEvent(e)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean = press.onEvent(e, onGlass(e.x, e.y)) || e.actionMasked == MotionEvent.ACTION_DOWN && onGlass(e.x, e.y)

        override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
            if (disallow) press.cancel()
            super.requestDisallowInterceptTouchEvent(disallow)
        }

        private fun onGlass(x: Float, y: Float): Boolean {
            val k = view.unitPx
            val l = view.left + GlassWidgetView.PAD_L * k; val t = view.top + GlassWidgetView.PAD_T * k
            return x >= l && x <= view.right - GlassWidgetView.PAD_R * k && y >= t && y <= view.bottom - GlassWidgetView.PAD_B * k
        }

        fun cancelPress() = press.cancel()

        /** This window is going: the widget a gesture was forwarded to never gets its end, so its press is dropped. */
        fun endForward() { forwardTo?.cancelPress(); forwardTo = null }

        fun forgetForward(target: Frame) { if (forwardTo === target) forwardTo = null }

        /** This widget's glass on screen, from layout positions. */
        fun glassRect(out: RectF): RectF {
            val k = view.unitPx
            getLocationOnScreen(loc)
            val vx = loc[0] + view.left; val vy = loc[1] + view.top
            return out.apply { set(vx + GlassWidgetView.PAD_L * k, vy + GlassWidgetView.PAD_T * k, vx + view.width - GlassWidgetView.PAD_R * k, vy + view.height - GlassWidgetView.PAD_B * k) }
        }

        // Where the window really landed, against where it should be: a firmware that shifts overlay
        // windows (by the status bar, say) is corrected, so the glass sits where it was dropped. Measured
        // from the window and the view's layout position, not where the entry animation draws it.
        fun recheck() { corrected = false; correct() }

        private fun correct() {
            if (corrected || !attached || targetX.isNaN() || view.width <= 0) return
            corrected = true
            getLocationOnScreen(loc)
            val k = view.unitPx
            val spot = spots[id] ?: extra[id] ?: return
            val vx = loc[0] + view.left; val vy = loc[1] + view.top
            val actualX = if (spot.right) vx + view.width - GlassWidgetView.PAD_R * k else vx + GlassWidgetView.PAD_L * k
            val actualY = if (spot.bottom) vy + view.height - GlassWidgetView.PAD_B * k else vy + GlassWidgetView.PAD_T * k
            val dx = (targetX - actualX).roundToInt(); val dy = (targetY - actualY).roundToInt()
            if (abs(dx) <= 1 && abs(dy) <= 1) return
            val nx = lp.x + if (spot.right) -dx else dx
            val ny = lp.y + if (spot.bottom) -dy else dy
            val reach = (MAX_CORRECTION * context.resources.displayMetrics.density).roundToInt()
            if (abs(nx - placedX) > reach || abs(ny - placedY) > reach) return
            lp.x = nx; lp.y = ny
            try { wm.updateViewLayout(this, lp) } catch (e: Exception) { Log.w(TAG, "correct: ${e.message}") }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : GlassWidgetView> widget(id: String): T? = frames[id]?.view as T?

    fun setSpots(next: Map<String, Spot>) {
        if (next == spots) return
        spots = next
        extra.keys.removeAll(next.keys)
        for (f in frames.values) place(f)
    }

    private fun spotOf(f: Frame): Spot {
        spots[f.id]?.let { return it }
        extra[f.id]?.let { return it }
        // a widget the user hasn't placed yet takes the first free space, clear of every placed widget,
        // shown or not (charging keeps its place while it waits for a charge)
        val dm = realMetrics()
        val k = k()
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val taken = (spots.keys + extra.keys).filter { it != f.id }.map { rectOf(it, w, h) }
        val r = freeSpot(f.view.baseW * k, f.view.baseH * k, taken, w, h, MARGIN * context.resources.displayMetrics.density, GlassWidgetView.GAP * k)
        return Spot(r.left / w, r.bottom / h, right = false, bottom = true).also { extra[f.id] = it }
    }

    // at the widget's full size, from its spot: what it takes up whatever shape it shows now
    private fun rectOf(id: String, w: Float, h: Float): RectF {
        val s = spots[id] ?: extra.getValue(id)
        val k = k() * s.size
        val size = frames[id]?.view?.let { it.baseW to it.baseH } ?: GlassWidgets.size(id)
        val sw = size.first * k; val sh = size.second * k
        val x = s.x * w; val y = s.y * h
        val l = if (s.right) x - sw else x; val t = if (s.bottom) y - sh else y
        return RectF(l, t, l + sw, t + sh)
    }

    init { handler.postDelayed(recheck, RECHECK_MS) }

    // a window that goes (arrange mode's) can move the navigation bar's frame without a layout here
    private val recheckOnce = Runnable { if (!hiddenNow) for (f in frames.values) if (f.attached) f.recheck() }

    /** Check every window against its spot shortly, rather than at the next periodic check. */
    fun recheckSoon() {
        handler.removeCallbacks(recheckOnce)
        handler.postDelayed(recheckOnce, 150L)
        handler.postDelayed(recheckOnce, 600L)
    }

    override fun show(ids: List<String>) {
        if (ids == shown) return
        for ((i, id) in ids.withIndex()) if (id !in frames) {
            val v = GlassWidgets.create(id, context, env, ticker).also { it.stale = staleNow }
            val f = Frame(id, v)
            frames[id] = f
            place(f)
            if (hiddenNow) f.visibility = FrameLayout.INVISIBLE
            try { wm.addView(f, f.lp); f.attached = true } catch (e: Exception) { Log.e(TAG, "addView $id: ${e.message}") }
            if (!hiddenNow) v.enter(i * 70L)
        }
        for (id in shown) if (id !in ids) frames.remove(id)?.let { f ->
            leaving += f
            // a hold on a widget that's leaving would otherwise open arrange mode after it's gone
            f.cancelPress()
            if (hiddenNow) drop(f) else { f.view.exit(); f.postDelayed({ drop(f) }, GlassWidgetView.EXIT_MS + 40) }
        }
        shown = ids
        ticker.kick()
    }

    private fun drop(f: Frame) {
        if (!leaving.remove(f)) return
        f.cancelPress()
        f.endForward()
        for (o in frames.values) o.forgetForward(f)
        f.removeCallbacks(f.hideWindow)
        f.view.release()
        if (f.attached) try { wm.removeView(f) } catch (_: Exception) { }
        f.attached = false
    }

    private fun place(f: Frame) {
        val spot = spotOf(f)
        f.view.sizeK = spot.size
        val dm = realMetrics()
        val k = f.view.unitPx
        val cx = spot.x * dm.widthPixels; val cy = spot.y * dm.heightPixels
        val lp = f.lp
        lp.gravity = (if (spot.right) Gravity.END else Gravity.START) or (if (spot.bottom) Gravity.BOTTOM else Gravity.TOP)
        lp.x = (if (spot.right) dm.widthPixels - cx - GlassWidgetView.PAD_R * k else cx - GlassWidgetView.PAD_L * k).roundToInt()
        lp.y = (if (spot.bottom) dm.heightPixels - cy - GlassWidgetView.PAD_B * k else cy - GlassWidgetView.PAD_T * k).roundToInt()
        (f.view.layoutParams as? FrameLayout.LayoutParams)?.let { it.gravity = lp.gravity; f.view.layoutParams = it }
        f.targetX = cx; f.targetY = cy; f.corrected = false
        f.placedX = lp.x; f.placedY = lp.y
        if (f.attached) try { wm.updateViewLayout(f, lp) } catch (e: Exception) { Log.w(TAG, "place ${f.id}: ${e.message}") }
    }

    private fun k() = context.resources.displayMetrics.density * env.scale

    private fun realMetrics(): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
    }

    override fun setHidden(hidden: Boolean) {
        if (hidden == hiddenNow) return
        hiddenNow = hidden
        env.hidden = hidden
        for ((i, id) in shown.withIndex()) {
            val f = frames[id] ?: continue
            f.removeCallbacks(f.hideWindow)
            if (hidden) {
                f.cancelPress()
                f.view.exit()
                f.postDelayed(f.hideWindow, GlassWidgetView.EXIT_MS + 60)
            } else {
                f.visibility = FrameLayout.VISIBLE
                f.view.enter(i * 70L)
            }
        }
        envChanged()
    }

    override fun setStale(stale: Boolean) {
        staleNow = stale
        for (f in frames.values) f.view.stale = stale
    }

    override fun envChanged() { for (f in frames.values) f.view.envChanged() }

    override fun themeChanged() { for (f in frames.values) f.view.themeChanged() }

    override fun rescale() {
        for (f in frames.values) { f.view.requestLayout(); place(f) }
        envChanged()
    }

    override fun invalidateWidgets() { for (f in frames.values) f.view.invalidate() }

    // where each widget lives: its saved place (a widget may be hidden, mid-animation or not laid out yet)
    override fun shells(): Map<String, RectF> {
        val dm = realMetrics()
        val out = LinkedHashMap<String, RectF>()
        for (id in shown) {
            if (spots[id] == null && extra[id] == null) continue
            val r = rectOf(id, dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
            // the shell's current size (a RoadSense pill), packed against its anchored corner
            val f = frames[id]
            if (f != null && f.view.width > 0) {
                val k = f.view.unitPx
                val sw = f.view.width - (GlassWidgetView.PAD_L + GlassWidgetView.PAD_R) * k
                val sh = f.view.height - (GlassWidgetView.PAD_T + GlassWidgetView.PAD_B) * k
                val s = spots[id] ?: extra.getValue(id)
                if (s.right) r.left = r.right - sw else r.right = r.left + sw
                if (s.bottom) r.top = r.bottom - sh else r.bottom = r.top + sh
            }
            out[id] = r
        }
        return out
    }

    /** Where every placed widget lives, shown or not (charging waiting for a charge keeps its place). */
    fun placed(): Map<String, RectF> {
        val dm = realMetrics()
        val out = LinkedHashMap<String, RectF>(shells())
        for (id in spots.keys + extra.keys) if (id !in out) out[id] = rectOf(id, dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        return out
    }

    // the topmost other widget whose glass is under a screen point (later windows sit on top)
    private val probe = RectF()
    private fun frameUnder(except: Frame, rawX: Float, rawY: Float): Frame? {
        if (hiddenNow) return null
        var hit: Frame? = null
        for (f in frames.values) {
            if (f === except || !f.attached || f.visibility != FrameLayout.VISIBLE) continue
            if (f.glassRect(probe).contains(rawX, rawY)) hit = f
        }
        return hit
    }

    override fun release() {
        handler.removeCallbacks(recheck)
        handler.removeCallbacks(recheckOnce)
        for (f in frames.values + leaving) {
            f.removeCallbacks(f.hideWindow)
            f.cancelPress()
            f.endForward()
            f.view.release()
            if (f.attached) try { wm.removeView(f) } catch (_: Exception) { }
            f.attached = false
        }
        frames.clear(); leaving.clear()
        shown = emptyList()
    }

    companion object {
        private const val TAG = "GlassFreeLayout"
        private const val MARGIN = 24f
        private const val RECHECK_MS = 2000L
        /** A system or navigation bar's worth, with room to spare: no firmware offset is larger. */
        private const val MAX_CORRECTION = 160f
    }
}
