package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Every widget id, in the settings page's catalog order. */
object GlassWidgets {
    val IDS = listOf("battery", "charging", "trip", "drive", "dynamics", "roadsense", "power", "controls", "shortcuts")

    /** Id prefixes of user-designed widgets and of the ready-made ones the app ships. */
    const val CUSTOM = "custom:"
    const val BUNDLED = "bundled:"

    // main thread: the docs of the custom and ready-made widgets in use, filled by the overlay service
    private var docs: Map<String, CustomDoc> = emptyMap()

    fun isCustom(id: String) = id.startsWith(CUSTOM) || id.startsWith(BUNDLED)

    fun setDocs(next: Map<String, CustomDoc>) { docs = next }

    fun doc(id: String): CustomDoc? = docs[id]

    fun hasDocs() = docs.isNotEmpty()

    /** The custom and ready-made ids with a doc registered. */
    val customIds: Set<String> get() = docs.keys

    /** Each widget's glass in design units, for placing one that isn't on screen; kept equal to its view's size. */
    fun size(id: String): Pair<Float, Float> {
        if (isCustom(id)) return docs[id]?.let { it.w to it.h } ?: (BLANK_W to BLANK_H)
        return when (id) {
            "battery", "charging" -> 272f to 184f
            "drive", "dynamics" -> 520f to 184f
            "controls" -> 620f to 184f
            "shortcuts" -> 420f to 184f
            else -> 400f to 184f
        }
    }

    /** The short name arrange mode's tray shows under each widget. */
    fun label(id: String): String {
        if (isCustom(id)) return docs[id]?.name?.takeIf { it.isNotBlank() } ?: gs(R.string.glass_w_widget)
        return when (id) {
            "battery" -> gs(R.string.glass_w_battery)
            "charging" -> gs(R.string.glass_w_charging)
            "trip" -> gs(R.string.glass_w_trip)
            "drive" -> gs(R.string.glass_w_drive)
            "dynamics" -> gs(R.string.glass_w_dynamics)
            "roadsense" -> "RoadSense"
            "power" -> gs(R.string.glass_w_power)
            "controls" -> gs(R.string.glass_w_controls)
            else -> gs(R.string.glass_w_shortcuts)
        }
    }

    /** A custom or ready-made id is only shown once its doc is registered; one that isn't gets empty glass. */
    fun create(id: String, context: Context, env: GlassEnv, ticker: GlassTicker): GlassWidgetView {
        if (isCustom(id)) return docs[id]?.let { GlassCustomView(context, env, ticker, it) } ?: BlankView(context, env, ticker)
        return when (id) {
            "battery" -> GlassBatteryView(context, env, ticker)
            "charging" -> GlassChargingView(context, env, ticker)
            "trip" -> GlassTripView(context, env, ticker)
            "drive" -> GlassDriveView(context, env, ticker)
            "dynamics" -> GlassDynamicsView(context, env, ticker)
            "roadsense" -> GlassRoadSenseView(context, env, ticker)
            "power" -> GlassPowerView(context, env, ticker)
            "shortcuts" -> GlassShortcutsView(context, env, ticker)
            else -> GlassControlsView(context, env, ticker)
        }
    }

    private const val BLANK_W = 272f
    private const val BLANK_H = 184f

    @SuppressLint("ViewConstructor")
    private class BlankView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, BLANK_W, BLANK_H) {
        override fun drawBack(c: Canvas) {}
        override fun drawLive(c: Canvas, nowMs: Long) {}
        override fun drawFront(c: Canvas) {}
    }
}

/**
 * The first place for a [w]×[h] glass that overlaps none of [taken] or [avoid] (kept [gap] apart),
 * scanning rows from the bottom-left of a [sw]×[sh] screen inside [margin]. On a full screen, the
 * place that hides the least of each other widget (a small one wholly covered counts as much as a
 * big one), and of [avoid] far less still; the bottom-left corner if it doesn't fit the screen at all.
 */
internal fun freeSpot(w: Float, h: Float, taken: Collection<RectF>, sw: Float, sh: Float, margin: Float, gap: Float, avoid: Collection<RectF> = emptyList()): RectF {
    val step = max(1f, gap)
    val probe = RectF()
    var best: RectF? = null
    var bestCover = Float.MAX_VALUE
    var y = sh - margin - h
    while (y >= margin) {
        var x = margin
        while (x + w <= sw - margin) {
            probe.set(x - gap, y - gap, x + w + gap, y + h + gap)
            var cover = 0f
            for (t in taken) cover += hidden(t, probe)
            for (t in avoid) cover += hidden(t, probe) * AVOID_WEIGHT
            if (cover <= 0f) return RectF(x, y, x + w, y + h)
            if (cover < bestCover) { bestCover = cover; best = RectF(x, y, x + w, y + h) }
            x += step
        }
        y -= step
    }
    return best ?: RectF(margin, sh - margin - h, margin + w, sh - margin)
}

private const val AVOID_WEIGHT = 1000f

// the share of [a] that [b] covers
private fun hidden(a: RectF, b: RectF) =
    max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top)) / max(1f, a.width() * a.height())

/** Where the widgets are on screen: one stacked window, a window per widget, or the arrange layer. */
interface GlassSurface {
    fun <T : GlassWidgetView> widget(id: String): T?
    fun show(ids: List<String>)
    fun setHidden(hidden: Boolean)
    fun setStale(stale: Boolean)
    fun envChanged()
    fun themeChanged()
    fun rescale()
    fun invalidateWidgets()
    /** Each shown widget's glass on screen, in px. */
    fun shells(): Map<String, RectF>
    fun release()
}

/**
 * A long press on a widget's glass (not its shadow margin) asks to arrange the widgets. The parent
 * feeds it every event it sees; once it fires, the parent takes the gesture so the widget lets go.
 */
internal class ArrangePress(private val view: View, private val onFire: () -> Unit) {
    private var downX = 0f
    private var downY = 0f
    private var armed = false
    var fired = false
        private set
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val fire = Runnable {
        if (!armed) return@Runnable
        armed = false
        fired = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onFire()
    }

    /** True once the press became a long press: the caller intercepts from then on. */
    fun onEvent(e: MotionEvent, onGlass: Boolean): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                fired = false
                armed = onGlass
                downX = e.rawX; downY = e.rawY
                if (armed) view.postDelayed(fire, LONG_MS)
            }
            MotionEvent.ACTION_MOVE -> if (armed && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) cancel()
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancel()
        }
        return fired
    }

    /** A widget that handles its own long press (a shortcut key) takes the gesture first. */
    fun cancel() {
        armed = false
        view.removeCallbacks(fire)
    }

    companion object {
        const val LONG_MS = 650L
    }
}
