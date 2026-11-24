package com.overdrive.app.overlay.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.Choreographer
import androidx.core.graphics.PathParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Critically damped (numbers, no overshoot) or lightly under-damped (liquid, a slight settle) spring. */
class Spring(initial: Float, private val omega: Float, private val zeta: Float) {
    var value = initial; private set
    var target = initial; private set
    var velocity = 0f; private set
    var moving = false; private set

    fun set(t: Float, instant: Boolean) {
        target = t
        if (instant) snap(t) else if (t != value || velocity != 0f) moving = true
    }

    fun snap(t: Float = target) {
        target = t; value = t; velocity = 0f; moving = false
    }

    /** Advances by [dt] seconds; returns true while still moving. */
    fun step(dt: Float): Boolean {
        if (!moving) return false
        val h = dt / 4f
        repeat(4) {
            val a = -omega * omega * (value - target) - 2f * zeta * omega * velocity
            velocity += a * h
            value += velocity * h
        }
        val scale = max(1f, abs(target))
        if (abs(value - target) < 1e-4f * scale && abs(velocity) < 1e-3f * scale) snap()
        return moving
    }

    companion object {
        fun number(v: Float = 0f) = Spring(v, 15f, 1f)
        fun liquid(v: Float = 0f) = Spring(v, 11f, 0.7f)
        fun shape(v: Float = 0f) = Spring(v, 13f, 0.86f)
    }
}

/**
 * State every widget reads: theme, driving, reduce motion, hidden, core offline. Loop rules:
 * ambient loops run only when parked; drive loops run parked or driving; both stop under reduce
 * motion; urgent loops (hazards) always run. Nothing runs while hidden or stale.
 */
class GlassEnv {
    var theme: GlassTheme = GlassTheme.NIGHT
    var driving = false
    var reduceMotion = false
    var hidden = false
    var coreOffline = false
    var lastSeenMin = 0
    var units = "bar"
    /** Distances, speeds and heights in miles, feet and yards: the unit picked for trips. */
    var miles = false
    var tint = 0.82f
    var scale = 1f
    /** Data the launcher summary doesn't carry yet renders in its degraded form. */
    var powerLive = false
    var roadLive = false
    var controlsLive = false

    /** Everything but [hidden], for a window that shows and hides on its own (the floating button). */
    fun copyFrom(o: GlassEnv) {
        theme = o.theme; driving = o.driving; reduceMotion = o.reduceMotion; coreOffline = o.coreOffline
        lastSeenMin = o.lastSeenMin; units = o.units; miles = o.miles; tint = o.tint; scale = o.scale
        powerLive = o.powerLive; roadLive = o.roadLive; controlsLive = o.controlsLive
    }

    fun allows(kind: Loop, stale: Boolean): Boolean = when {
        hidden || stale -> false
        kind == Loop.URGENT -> true
        kind == Loop.DRIVE -> !reduceMotion
        else -> !driving && !reduceMotion
    }

    enum class Loop { AMBIENT, DRIVE, URGENT }
}

/** Distances, speeds, consumption and heights in the unit picked for trips: the data stays metric underneath. */
object GlassUnits {
    private const val KM_PER_MI = 1.609344f
    fun usesMiles(config: org.json.JSONObject) = config.optJSONObject("tripAnalytics")?.optString("distanceUnit") == "mi"
    fun dist(km: Float, miles: Boolean) = if (miles) km / KM_PER_MI else km
    fun distUnit(miles: Boolean) = if (miles) "mi" else "km"
    fun speed(kmh: Float, miles: Boolean) = if (miles) kmh / KM_PER_MI else kmh
    fun speedUnit(miles: Boolean) = if (miles) "mph" else "km/h"
    /** Wh per km as kWh per 100 km, or per 100 miles. */
    fun consumption(whPerKm: Int, miles: Boolean): String =
        String.format(java.util.Locale.US, "%.1f %s", consumptionValue(whPerKm, miles), consumptionUnit(miles))
    fun consumptionValue(whPerKm: Int, miles: Boolean) = if (miles) whPerKm * KM_PER_MI / 10f else whPerKm / 10f
    fun consumptionValue(kwhPer100Km: Float, miles: Boolean) = if (miles) kwhPer100Km * KM_PER_MI else kwhPer100Km
    fun consumptionUnit(miles: Boolean) = if (miles) "kWh/100 mi" else "kWh/100 km"
    /** A short way ahead (RoadSense's countdown): metres, or yards. */
    fun near(m: Float, miles: Boolean) = if (miles) m * 1.0936133f else m
    fun nearUnit(miles: Boolean) = if (miles) "yd" else "m"
    /** Altitude: metres, or feet. */
    fun height(m: Float, miles: Boolean) = if (miles) m * 3.2808399f else m
    fun heightUnit(miles: Boolean) = if (miles) "ft" else "m"
}

/** One Choreographer callback for every widget, scheduled only while something is moving. */
class GlassTicker {
    private val widgets = ArrayList<GlassWidgetView>(8)
    private var scheduled = false
    private var lastNanos = 0L
    private val callback = Choreographer.FrameCallback { now -> frame(now) }

    fun add(w: GlassWidgetView) { if (w !in widgets) widgets.add(w) }
    fun remove(w: GlassWidgetView) { widgets.remove(w) }

    fun kick() {
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (scheduled) Choreographer.getInstance().removeFrameCallback(callback)
        scheduled = false
        lastNanos = 0L
    }

    private fun frame(now: Long) {
        scheduled = false
        val dt = if (lastNanos == 0L) 1f / 60f else min(0.05f, (now - lastNanos) / 1e9f)
        lastNanos = now
        val ms = now / 1_000_000L
        var busy = false
        for (i in widgets.indices) if (widgets[i].tick(ms, dt)) busy = true
        if (busy) kick() else lastNanos = 0L
    }
}

/** Barlow from the APK's assets, with tabular digits so changing numbers don't jitter. */
object GlassFonts {
    @Volatile private var cache: Array<Typeface>? = null

    fun get(context: Context, weight: Int): Typeface {
        val faces = cache ?: synchronized(this) {
            cache ?: arrayOf(500, 600, 700).map { w ->
                try { Typeface.createFromAsset(context.assets, "glass/barlow-$w.ttf") } catch (_: Throwable) { Typeface.DEFAULT }
            }.toTypedArray().also { cache = it }
        }
        return when {
            weight >= 700 -> faces[2]
            weight >= 600 -> faces[1]
            else -> faces[0]
        }
    }

    fun paint(context: Context, weight: Int, sizePx: Float, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = get(context, weight)
        textSize = sizePx
        this.color = color
        fontFeatureSettings = "'tnum'"
        isSubpixelText = true
    }
}

/** A paint's alpha multiplies its shader, so a shader always goes on at full alpha; set any fade after this. */
fun Paint.shade(s: android.graphics.Shader?): Paint { shader = s; alpha = 255; return this }

/**
 * Draws a unit radial shader (centre 0,0, radius 1, never given a local matrix) as an ellipse by
 * moving the canvas, not the shader: a shader whose local matrix changes is rebuilt natively (and
 * allocates) on its next draw, which would happen every frame for anything that moves.
 */
fun android.graphics.Canvas.unitOval(p: Paint, s: android.graphics.Shader, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Int = 255) {
    save()
    translate(cx, cy)
    scale(maxOf(1e-3f, rx), maxOf(1e-3f, ry))
    p.shade(s).alpha = alpha
    drawCircle(0f, 0f, 1f, p)
    restore()
    p.alpha = 255
}

object GlassPaths {
    private val cache = HashMap<String, Path>()

    /** SVG path data → Path, parsed once per string. */
    fun svg(d: String): Path = cache.getOrPut(d) { PathParser.createPathFromPathData(d) }
}

/**
 * Soft shadows are baked with a CPU box blur: BlurMaskFilter draws wrong on this head unit and
 * there is no live blur below Android 12. Runs once per size and theme.
 */
object GlassBlur {
    fun blurAlpha(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val a = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        for (i in a.indices) a[i] = a[i] ushr 24
        val tmp = IntArray(w * h)
        repeat(3) {
            boxH(a, tmp, w, h, radius)
            boxV(tmp, a, w, h, radius)
        }
        for (i in a.indices) a[i] = a[i].coerceIn(0, 255) shl 24
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = sum / div
                sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / div
                sum += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }
}
