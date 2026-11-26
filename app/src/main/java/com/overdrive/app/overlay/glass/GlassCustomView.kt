package com.overdrive.app.overlay.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A widget the user designed, drawn on the same glass shell, theme, baked layers, springs and loop
 * rules as the built-in widgets. Layers stack in file order: static ones below the first live layer
 * bake into the back bitmap, static ones above the last live layer into the front. Expressions run once
 * per reading in [bind]; frames only read their results. A file that makes it throw stops this widget,
 * never the overlay.
 */
@SuppressLint("ViewConstructor")
class GlassCustomView(context: Context, env: GlassEnv, ticker: GlassTicker, val doc: CustomDoc) :
    GlassWidgetView(context, env, ticker, doc.w, doc.h) {

    private val t: GlassTheme get() = env.theme
    private var data: Map<String, Any?> = emptyMap()
    // what expressions read: the bound values, or one live sample while the sparklines record it
    private var reading: Map<String, Any?> = data
    private var bound = false
    private var forcing = false
    // set once anything this file drives throws: from then on it neither animates nor draws
    private var broken = false
    private var buildError: Throwable? = null
    private var evalDriving = env.driving
    private var evalMiles = env.miles
    private var evalUnits = env.units
    private val scope = CustomExpr.Scope { lookup(it) }
    private val loopStart = SystemClock.uptimeMillis()
    private val box = RectF()

    private val layers: Array<Layer> = doc.layers.mapNotNull { s ->
        try { layerFor(s) } catch (e: Throwable) { if (buildError == null) buildError = e; null }
    }.toTypedArray()
    private val firstLive = layers.indexOfFirst { it.live }.let { if (it < 0) layers.size else it }
    private val lastLive = layers.indexOfLast { it.live }
    private val pulses = layers.filterIsInstance<PulseLayer>().toTypedArray()
    private val sparks = layers.filterIsInstance<SparkLayer>().toTypedArray()
    private val objects = layers.filterIsInstance<ObjectLayer>().toTypedArray()

    private var springsMoving = false
    private var objectsBusy = false
    // a borrowed car or road settling after new data animates at full rate, its loops alone at 30 fps
    private var settleUntil = 0L
    private var frameAt = 0L

    init {
        // a cell's glass joins the live pass when a live-pass layer lies under it, its metal when one lies over it
        for (i in layers.indices) (layers[i] as? CellLayer)?.place(i > firstLive, i < lastLive)
        // constants (and dashes for every signal) show before the first reading
        val e = buildError
        if (e != null) fail(e) else guard { evaluate(true) }
    }

    private fun layerFor(s: CustomDoc.Spec): Layer? = when (s.type) {
        "ring" -> RingLayer(s)
        "bar" -> BarLayer(s)
        "text" -> TextLayer(s)
        "spark" -> SparkLayer(s)
        "capsule" -> CellLayer(s)
        "pulse" -> PulseLayer(s)
        "shape" -> ShapeLayer(s)
        "car" -> CarLayer(s)
        "road" -> RoadLayer(s)
        else -> null
    }

    private inline fun guard(block: () -> Unit) {
        if (broken) return
        try { block() } catch (e: Throwable) { fail(e) }
    }

    private fun fail(e: Throwable) {
        if (broken) return
        broken = true
        Log.w(TAG, "widget ${doc.id} stopped: $e")
        invalidate()
    }

    private fun lookup(name: String): Any? {
        val m = reading
        if (m.containsKey(name)) {
            val v = m[name]
            return if (v is Number && v !is Double) v.toDouble() else v
        }
        return when (name) {
            "night" -> t.night
            "driving" -> env.driving
            "parked" -> !env.driving
            "pi" -> PI
            CustomExpr.MILES -> env.miles
            "dist_unit" -> GlassUnits.distUnit(env.miles)
            "speed_unit" -> GlassUnits.speedUnit(env.miles)
            "near_unit" -> GlassUnits.nearUnit(env.miles)
            "height_unit" -> GlassUnits.heightUnit(env.miles)
            "consumption_unit" -> GlassUnits.consumptionUnit(env.miles)
            CustomExpr.TYRE_UNIT -> GlassTyres.unit(env.units)
            else -> token(t, name)?.let { CustomExpr.Col(it) }
        }
    }

    private fun eval(p: CustomDoc.Prop?): Any? = when (p) {
        null, is CustomDoc.Template -> null
        is CustomDoc.Fixed -> p.v
        is CustomDoc.Bound -> CustomExpr.eval(p.node, scope)
    }

    private fun signal(key: String): Float? = CustomExpr.num(lookup(key))?.takeIf { abs(it) <= CustomExpr.MAX_NUM }?.toFloat()

    /**
     * New readings ([CustomSignals.values]): every expression runs here, once. Unchanged readings are a
     * no-op; [force] re-runs them (a theme or unit change).
     */
    fun bind(values: Map<String, Any?>, animate: Boolean, force: Boolean = false) {
        if (broken || (!force && bound && values == data)) return
        guard {
            val first = !bound
            bound = true
            data = values
            forcing = force
            try { evaluate(first || !animate || env.reduceMotion) } finally { forcing = false }
            val now = SystemClock.uptimeMillis()
            for (s in sparks) s.record(now)
            if (force) layersChanged()
        }
    }

    /** One live reading at [atMs] (uptime) for the sparklines; they keep about one point a second. */
    fun addSample(atMs: Long, values: Map<String, Any?>) {
        if (broken || sparks.isEmpty()) return
        guard {
            reading = values
            var drew = false
            try { for (s in sparks) if (s.sample(atMs)) drew = true } finally { reading = data }
            if (drew) invalidate()
        }
    }

    private fun evaluate(instant: Boolean) {
        reading = data
        evalDriving = env.driving; evalMiles = env.miles; evalUnits = env.units
        val now = SystemClock.uptimeMillis()
        var bake = false
        for (l in layers) if (l.evaluate(instant, now)) bake = true
        if (bake) layersChanged()
        requestFrames()
        invalidate()
    }

    private fun settle(now: Long) { settleUntil = max(settleUntil, now + SETTLE_MS) }

    override fun animate(nowMs: Long, dt: Float): Boolean {
        if (broken) return false
        return try { stepAll(nowMs, dt) } catch (e: Throwable) { fail(e); false }
    }

    private fun stepAll(nowMs: Long, dt: Float): Boolean {
        frameAt = nowMs
        var moving = false
        for (l in layers) if (l.step(dt)) moving = true
        springsMoving = moving
        var busy = false
        for (o in objects) if (o.tick(nowMs, dt)) busy = true
        objectsBusy = busy
        return moving || busy || loopRunning()
    }

    private fun loopRunning(): Boolean {
        if (pulses.isEmpty() || !env.allows(GlassEnv.Loop.AMBIENT, stale)) return false
        for (p in pulses) if (p.visible) return true
        return false
    }

    // a loop alone runs at 30 fps; springs get every frame
    override val minFrameMs: Long
        get() = if (springsMoving || frameAt < settleUntil || !(objectsBusy || loopRunning())) 0L else LOOP_FRAME_MS

    override fun onThemeChanged() = guard {
        for (l in layers) l.themeChanged()
        evaluate(true)
    }

    override fun envChanged() {
        guard {
            for (o in objects) o.syncEnv()
            // the driving, parked and unit builtins follow the car between readings
            if (bound && (env.driving != evalDriving || env.miles != evalMiles || env.units != evalUnits)) evaluate(env.reduceMotion)
        }
        super.envChanged()
    }

    override fun onDraw(c: Canvas) {
        if (broken) return
        val saved = c.saveCount
        try { super.onDraw(c) } catch (e: Throwable) { c.restoreToCount(saved); fail(e) }
    }

    override fun drawBack(c: Canvas) {
        for (i in 0 until firstLive) layers[i].draw(c, 0L)
        if (firstLive < layers.size) (layers[firstLive] as? CellLayer)?.back(c)
    }

    override fun drawLive(c: Canvas, nowMs: Long) {
        for (i in firstLive..lastLive) layers[i].draw(c, nowMs)
    }

    override fun drawFront(c: Canvas) {
        if (lastLive >= 0) (layers[lastLive] as? CellLayer)?.front(c)
        for (i in max(lastLive + 1, firstLive) until layers.size) layers[i].draw(c, 0L)
    }

    private fun fade(c: Int, k: Float) = GlassColor.alpha(c, (c ushr 24) / 255f * k)

    private fun level(v: Float?, s: Spring?): Float = if (v == null) 0f else (if (s != null) s.value else v).coerceIn(0f, 1f)

    // ── layers ───────────────────────────────────────────────────────────────

    private abstract inner class Layer(val spec: CustomDoc.Spec, always: Boolean) {
        /** Drawn per frame; the rest bake. */
        val live = always || spec.bound
        private val visibleP = spec.props["visible"]
        private val opacityP = spec.props["opacity"]
        var shown = true
            private set
        var alpha = 1f
            private set
        val visible get() = shown && alpha > 0f
        protected val motion = (spec.fixed("motion") as? String)?.takeIf { it == "number" || it == "liquid" }
        protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val rect = RectF()
        /** Part of it is baked, so a change in its visibility or opacity re-bakes (a glass cell). */
        protected open val bakes = false

        protected fun geo(key: String, def: Float) = (spec.fixed(key) as? Double)?.toFloat() ?: def
        protected fun word(key: String) = spec.fixed(key) as? String
        protected fun value(key: String): Any? = eval(spec.props[key])
        protected fun num(key: String): Float? = CustomExpr.num(value(key))?.toFloat()

        protected fun color(key: String, def: Int?): Int? {
            if (!spec.props.containsKey(key)) return def
            return when (val v = value(key)) {
                is CustomExpr.Col -> v.argb
                is String -> if (v == "none") null else token(t, v) ?: CustomExpr.parseColor(v) ?: def
                else -> def
            }
        }

        protected fun spring(old: Spring?, v: Float?, instant: Boolean): Spring? {
            val m = motion ?: return null
            if (v == null) return old
            val s = old ?: if (m == "liquid") Spring.liquid(v) else Spring.number(v)
            s.set(v, instant)
            return s
        }

        /** Re-reads every property; true when the baked layers must be redrawn. */
        fun evaluate(instant: Boolean, now: Long): Boolean {
            val was = shown
            val wasAlpha = alpha
            shown = visibleP == null || CustomExpr.truthy(eval(visibleP))
            alpha = if (opacityP == null) 1f else (CustomExpr.num(eval(opacityP))?.toFloat() ?: 1f).coerceIn(0f, 1f)
            update(instant, now)
            return bakes && (was != shown || wasAlpha != alpha)
        }

        protected abstract fun update(instant: Boolean, now: Long)
        open fun step(dt: Float): Boolean = false
        open fun themeChanged() {}

        fun draw(c: Canvas, now: Long) { if (visible) render(c, now, alpha) }
        protected abstract fun render(c: Canvas, now: Long, k: Float)
    }

    // Ring and bar share value animation and colors, but keep their own geometry.
    private abstract inner class MeterLayer(spec: CustomDoc.Spec) : Layer(spec, false) {
        private var v: Float? = null
        private var s: Spring? = null
        protected var color: Int? = null
            private set
        protected var lit = 0
            private set
        protected var track: Int? = null
            private set

        final override fun update(instant: Boolean, now: Long) {
            v = num("value")
            s = spring(s, v, instant)
            color = color("color", t.glowAccent)?.also { lit = GlassColor.lighten(it, 0.6f) }
            track = color("track", t.etch)
        }

        final override fun step(dt: Float): Boolean { val sp = s; return sp != null && sp.step(dt) }

        protected fun fraction(): Float = level(v, s)
    }

    private inner class RingLayer(spec: CustomDoc.Spec) : MeterLayer(spec) {
        private val cx = geo("cx", 80f)
        private val cy = geo("cy", 92f)
        private val r = geo("r", 54f)
        private val w = geo("width", 12f).coerceIn(0f, 200f)
        private val start = geo("start", 135f)
        private val sweep = geo("sweep", 270f)
        private val glow = geo("glow", 1f).coerceIn(0f, 4f)
        private val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        private val inner = (r - w * 0.18f).let { RectF(cx - it, cy - it, cx + it, cy + it) }

        override fun render(c: Canvas, now: Long, k: Float) {
            val f = fraction()
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            track?.let { paint.strokeWidth = w; paint.color = fade(it, k); c.drawArc(oval, start, sweep, false, paint) }
            if (f <= 0.002f) return
            val col = color ?: return
            val g = glow * (if (t.night) 1f else 0.6f)
            for (j in GLOW_W.indices) {
                paint.strokeWidth = w + GLOW_W[j]; paint.color = fade(col, GLOW_A[j] * g * k)
                c.drawArc(oval, start, sweep * f, false, paint)
            }
            paint.strokeWidth = w; paint.color = fade(col, k)
            c.drawArc(oval, start, sweep * f, false, paint)
            paint.strokeWidth = max(1f, w * 0.28f); paint.color = fade(lit, 0.55f * k)
            c.drawArc(inner, start, sweep * f, false, paint)
        }
    }

    private inner class BarLayer(spec: CustomDoc.Spec) : MeterLayer(spec) {
        private val x = geo("x", 26f)
        private val y = geo("y", 140f)
        private val w = geo("w", 220f)
        private val h = geo("h", 9f).coerceAtLeast(0f)
        private val glow = geo("glow", 1f).coerceIn(0f, 4f)
        private val rr = h / 2f

        override fun render(c: Canvas, now: Long, k: Float) {
            val f = fraction()
            paint.style = Paint.Style.FILL
            track?.let { paint.color = fade(it, k); rect.set(x, y, x + w, y + h); c.drawRoundRect(rect, rr, rr, paint) }
            if (f <= 0.002f) return
            val col = color ?: return
            val fw = max(h, w * f)
            val g = glow * (if (t.night) 1f else 0.6f)
            for (j in GLOW_W.indices) {
                val e = GLOW_W[j] / 2f
                paint.color = fade(col, GLOW_A[j] * g * k)
                rect.set(x - e, y - e, x + fw + e, y + h + e); c.drawRoundRect(rect, rr + e, rr + e, paint)
            }
            paint.color = fade(col, k)
            rect.set(x, y, x + fw, y + h); c.drawRoundRect(rect, rr, rr, paint)
            val hw = fw - rr * 1.2f
            if (hw > 0f) {
                paint.color = fade(lit, 0.5f * k)
                rect.set(x + rr * 0.6f, y + h * 0.18f, x + rr * 0.6f + hw, y + h * 0.42f); c.drawRoundRect(rect, h * 0.12f, h * 0.12f, paint)
            }
        }
    }

    private inner class TextLayer(spec: CustomDoc.Spec) : Layer(spec, false) {
        private val x = geo("x", 26f)
        private val y = geo("y", 100f)
        private val size = geo("size", 24f).coerceIn(1f, 300f)
        private val align = word("align")
        private val tp = GlassFonts.paint(context, (spec.fixed("weight") as? Double)?.toInt() ?: 600, size, 0)
        private val up = GlassFonts.paint(context, 500, size * 0.5f, 0)
        private val textP = spec.props["text"]
        private val unitP = spec.props["unit"]
        // a template's parts (literal text and holes), or null for a single value
        private val parts: List<Any>? = (textP as? CustomDoc.Template)?.parts
        private val n = parts?.size ?: 0
        private val holeDec = IntArray(n) { (parts?.get(it) as? CustomExpr.Hole)?.decimals ?: 0 }
        private val holes = arrayOfNulls<Any>(n)
        private val holeNum = DoubleArray(n)
        private val holeSprings = arrayOfNulls<Spring>(n)
        // what each gliding number shows, as CustomExpr.fixed rounds it: the text is re-formatted only when that changes
        private val sig = LongArray(max(1, n))
        private var plain: Any? = null
        private var plainNum = Double.NaN
        private var plainSpring: Spring? = null
        private var wasMoving = false
        private var shownText = ""
        private var shownUnit = ""
        private var textW = 0f
        private var unitW = 0f
        private var color = 0

        override fun update(instant: Boolean, now: Long) {
            if (parts != null) {
                for (i in 0 until n) {
                    val hole = parts[i] as? CustomExpr.Hole ?: continue
                    val v = CustomExpr.eval(hole.node, scope)
                    val xv = CustomExpr.num(v)
                    holes[i] = v
                    holeNum[i] = xv ?: Double.NaN
                    holeSprings[i] = spring(holeSprings[i], xv?.toFloat(), instant)
                }
            } else {
                plain = value("text")
                val xv = CustomExpr.num(plain)
                plainNum = xv ?: Double.NaN
                plainSpring = spring(plainSpring, xv?.toFloat(), instant)
            }
            shownUnit = when (val u = unitP) {
                null -> ""
                is CustomDoc.Template -> buildString {
                    for (part in u.parts) {
                        if (part is CustomExpr.Hole) {
                            val v = CustomExpr.eval(part.node, scope)
                            append(holeText(v, CustomExpr.num(v) ?: Double.NaN, part.decimals ?: 0, null))
                        } else append(part as String)
                    }
                }
                else -> CustomExpr.text(value("unit"))
            }
            unitW = if (shownUnit.isEmpty()) 0f else up.measureText(shownUnit)
            color = color("color", t.text) ?: t.text
            refresh(true)
        }

        override fun step(dt: Float): Boolean {
            var moving = false
            for (s in holeSprings) if (s != null && s.step(dt)) moving = true
            val ps = plainSpring
            if (ps != null && ps.step(dt)) moving = true
            if (moving || wasMoving) refresh(false)
            wasMoving = moving
            return moving
        }

        private fun holeText(v: Any?, num: Double, dec: Int, s: Spring?): String = when {
            v == null -> "–"
            num.isNaN() -> CustomExpr.text(v)
            else -> CustomExpr.fixed(if (s != null) s.value.toDouble() else num, dec)
        }

        // the rounded magnitude and the sign, exactly what CustomExpr.fixed prints for [v]
        private fun digits(v: Double, dec: Int): Long = Math.round(abs(v) * POW10[dec]) * 2 + (if (v < 0) 1 else 0)

        private fun refresh(force: Boolean) {
            var changed = force
            val ps = plainSpring
            if (parts != null) {
                for (i in 0 until n) {
                    val s = holeSprings[i] ?: continue
                    if (holeNum[i].isNaN()) continue
                    val k = digits(s.value.toDouble(), holeDec[i])
                    if (k != sig[i]) { sig[i] = k; changed = true }
                }
            } else if (ps != null && !plainNum.isNaN()) {
                val k = digits(ps.value.toDouble(), 0)
                if (k != sig[0]) { sig[0] = k; changed = true }
            }
            if (!changed) return
            shownText = when {
                parts != null -> buildString {
                    for (i in 0 until n) {
                        val part = parts[i]
                        append(if (part is String) part else holeText(holes[i], holeNum[i], holeDec[i], holeSprings[i]))
                    }
                }
                textP == null -> ""
                ps != null && !plainNum.isNaN() -> CustomExpr.fixed(ps.value.toDouble(), 0)
                else -> CustomExpr.text(plain)
            }
            textW = tp.measureText(shownText)
        }

        override fun render(c: Canvas, now: Long, k: Float) {
            val gap = if (shownUnit.isEmpty()) 0f else size * 0.08f
            val total = textW + gap + unitW
            val x0 = when (align) { "center" -> x - total / 2f; "right" -> x - total; else -> x }
            tp.color = fade(color, k)
            c.drawText(shownText, x0, y, tp)
            if (shownUnit.isNotEmpty()) {
                up.color = fade(color, 0.5f * k)
                c.drawText(shownUnit, x0 + textW + gap, y, up)
            }
        }
    }

    private inner class SparkLayer(spec: CustomDoc.Spec) : Layer(spec, true) {
        private val x = geo("x", 150f)
        private val y = geo("y", 40f)
        private val w = geo("w", 200f)
        private val h = geo("h", 70f)
        private val winMs = (geo("window", 60f).coerceIn(10f, 300f) * 1000f).toLong()
        private val fill = geo("fill", 0.2f).coerceIn(0f, 1f)
        private val times = LongArray(SPARK_MAX)
        private val vals = FloatArray(SPARK_MAX)
        private var count = 0
        private val line = Path()
        private val area = Path()
        private var hasLine = false
        private var zeroY = 0f
        private var below = false
        private var cur: Float? = null
        private var color = 0
        private var shader: Shader? = null
        private var shaderColor = 0

        override fun update(instant: Boolean, now: Long) {
            cur = num("value")
            color = color("color", t.glowAccent) ?: t.glowAccent
            if (fill > 0f && (shader == null || shaderColor != color)) {
                shader = LinearGradient(0f, y, 0f, y + h, fade(color, fill), GlassColor.clear(color), Shader.TileMode.CLAMP)
                shaderColor = color
            }
        }

        override fun themeChanged() { shader = null }

        fun record(now: Long) = push(now, cur)

        /** True when the trace changed and shows. */
        fun sample(atMs: Long): Boolean = push(atMs, num("value"))

        private fun push(at: Long, v: Float?): Boolean {
            if (v == null) return false
            if (count > 0 && at - times[count - 1] < SPARK_GAP_MS) return false
            if (count == SPARK_MAX) {
                System.arraycopy(times, 1, times, 0, SPARK_MAX - 1)
                System.arraycopy(vals, 1, vals, 0, SPARK_MAX - 1)
                count--
            }
            times[count] = at; vals[count] = v; count++
            build()
            return visible
        }

        // the trace's paths are rebuilt per point, never per frame; the newest point sits at the right edge
        private fun build() {
            line.rewind(); area.rewind()
            hasLine = false
            if (count < 2) return
            val from = times[count - 1] - winMs
            var lo = 0f; var hi = 0f; var first = -1
            for (i in 0 until count) if (times[i] >= from - 1000L) { if (first < 0) first = i; lo = min(lo, vals[i]); hi = max(hi, vals[i]) }
            if (first < 0 || count - first < 2) return
            if (hi - lo < 1e-3f) hi = lo + 1f
            for (i in first until count) {
                val px = x + max(0f, (times[i] - from).toFloat() / winMs) * w
                val py = y + h - (vals[i] - lo) / (hi - lo) * h
                if (i == first) line.moveTo(px, py) else line.lineTo(px, py)
            }
            zeroY = (y + h - (0f - lo) / (hi - lo) * h).coerceIn(min(y, y + h), max(y, y + h))
            below = lo < 0f
            area.set(line)
            area.lineTo(x + max(0f, (times[count - 1] - from).toFloat() / winMs) * w, zeroY)
            area.lineTo(x + max(0f, (times[first] - from).toFloat() / winMs) * w, zeroY)
            area.close()
            hasLine = true
        }

        override fun render(c: Canvas, now: Long, k: Float) {
            if (!hasLine) return
            val sh = shader
            if (fill > 0f && sh != null) {
                paint.style = Paint.Style.FILL
                paint.shade(sh).alpha = (255f * k).roundToInt()
                c.drawPath(area, paint)
                paint.shader = null
                paint.alpha = 255
            }
            paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND; paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 6f; paint.color = fade(color, (if (t.night) 0.16f else 0.1f) * k)
            c.drawPath(line, paint)
            paint.strokeWidth = 2.2f; paint.color = fade(color, k)
            c.drawPath(line, paint)
            if (below) {
                paint.strokeWidth = 1f; paint.color = fade(t.text, 0.18f * k)
                c.drawLine(x, zeroY, x + w, zeroY, paint)
            }
        }
    }

    private inner class PulseLayer(spec: CustomDoc.Spec) : Layer(spec, true) {
        private val cx = geo("cx", 80f)
        private val cy = geo("cy", 92f)
        private val r = geo("r", 80f)
        private val period = max(0.6f, geo("period", 2.4f))
        private var color: Int? = null
        private var shader: RadialGradient? = null
        private var shaderColor = 0

        override fun update(instant: Boolean, now: Long) {
            val col = color("color", t.glowAccent)
            color = col
            if (col != null && (shader == null || col != shaderColor)) {
                shader = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(col, 0.5f), GlassColor.alpha(col, 0.2f), GlassColor.clear(col)),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                shaderColor = col
            }
        }

        override fun themeChanged() { shader = null }

        // an ambient loop: it breathes only parked, without reduce motion, with fresh data; otherwise it holds still
        override fun render(c: Canvas, now: Long, k: Float) {
            val sh = shader ?: return
            if (color == null) return
            var a = 0.6f
            var s = 1f
            if (env.allows(GlassEnv.Loop.AMBIENT, stale)) {
                val wave = (0.5 + 0.5 * sin((now - loopStart) / 1000.0 / period * 2.0 * PI)).toFloat()
                a = 0.35f + 0.65f * wave; s = 0.9f + 0.1f * wave
            }
            paint.style = Paint.Style.FILL
            c.unitOval(paint, sh, cx, cy, r * s, r * s, (a * k * (if (t.night) 1f else 0.7f) * 255f).roundToInt().coerceIn(0, 255))
        }
    }

    private inner class ShapeLayer(spec: CustomDoc.Spec) : Layer(spec, false) {
        private val x = geo("x", 0f)
        private val y = geo("y", 0f)
        private val w = geo("w", 40f)
        private val h = geo("h", 40f)
        private val radius = geo("radius", 0f).coerceAtLeast(0f)
        private val sw = geo("strokeWidth", 2f).coerceIn(0f, 100f)
        private val kind = word("shape") ?: "rect"
        private val path: Path? = if (kind != "path") null else word("d")?.let { d ->
            try { PathParser.createPathFromPathData(d) } catch (_: RuntimeException) { null }
        }
        private var fill: Int? = null
        private var stroke: Int? = null
        private var rot: Float? = null
        private var rs: Spring? = null

        override fun update(instant: Boolean, now: Long) {
            fill = color("fill", null)
            stroke = color("stroke", null)
            rot = num("rotate")
            rs = spring(rs, rot, instant)
        }

        override fun step(dt: Float): Boolean { val sp = rs; return sp != null && sp.step(dt) }

        override fun render(c: Canvas, now: Long, k: Float) {
            if (kind == "path" && path == null) return
            val r0 = rot
            val sp = rs
            val angle = if (r0 == null) 0f else if (sp != null) sp.value else r0
            c.save()
            if (angle != 0f) c.rotate(angle, x + w / 2f, y + h / 2f)
            if (path != null) c.translate(x, y)
            rect.set(x, y, x + w, y + h)
            paint.shader = null
            fill?.let { paint.style = Paint.Style.FILL; paint.color = fade(it, k); outline(c) }
            val st = stroke
            if (st != null && sw > 0f) { paint.style = Paint.Style.STROKE; paint.strokeWidth = sw; paint.color = fade(st, k); outline(c) }
            c.restore()
        }

        private fun outline(c: Canvas) {
            when {
                path != null -> c.drawPath(path, paint)
                kind == "circle" -> c.drawOval(rect, paint)
                else -> { val rr = min(radius, min(abs(w), abs(h)) / 2f); c.drawRoundRect(rect, rr, rr, paint) }
            }
        }
    }

    /**
     * The built-in battery cell. Its glass and metal bake below and above the live pass when nothing live
     * lies under or over the cell; otherwise they replay a recording in file order, so frames allocate nothing.
     */
    private inner class CellLayer(spec: CustomDoc.Spec) : Layer(spec, true) {
        private val x = geo("x", 26f)
        private val y = geo("y", 15f)
        private val w = geo("w", 62f).coerceAtLeast(10f)
        private val h = geo("h", 154f).coerceAtLeast(40f)
        // everything the cell paints: its shadows, the liquid's halo and the limit ring's ticks included
        private val area = RectF(x - w * 0.25f - 16f, y - 30f, x + w * 1.25f + 16f, y + h + 30f)
        private var cell: CellPainter? = null
        private var lvl: Float? = null
        private var ls: Spring? = null
        private var limit: Float? = null
        private val agitation = FloatArray(1)
        private var inlineBack = false
        private var inlineFront = false
        private var backPic: Picture? = null
        private var frontPic: Picture? = null
        override val bakes: Boolean get() = !inlineBack || !inlineFront

        fun place(back: Boolean, front: Boolean) { inlineBack = back; inlineFront = front }

        private fun painter() = cell ?: CellPainter(t, x, y, w, h, false).also { cell = it }

        private fun record(front: Boolean): Picture = Picture().also { pic ->
            val pc = pic.beginRecording(ceil(baseW).toInt(), ceil(baseH).toInt())
            if (front) painter().drawFront(pc) else painter().drawBack(pc)
            pic.endRecording()
        }

        override fun update(instant: Boolean, now: Long) {
            lvl = num("level")
            ls = spring(ls, lvl, instant)
            limit = num("limit")
            painter().setColor(0, color("color", t.glowAccent) ?: t.glowAccent)
            if (inlineBack && backPic == null) backPic = record(false)
            if (inlineFront && frontPic == null) frontPic = record(true)
        }

        override fun step(dt: Float): Boolean { val sp = ls; return sp != null && sp.step(dt) }
        override fun themeChanged() { cell = null; backPic = null; frontPic = null }

        fun back(c: Canvas) { if (!inlineBack && visible) faded(c, alpha, false) }
        fun front(c: Canvas) { if (!inlineFront && visible) faded(c, alpha, true) }

        // a baked part at the layer's opacity
        private fun faded(c: Canvas, k: Float, front: Boolean) {
            val n = if (k < 1f) c.saveLayerAlpha(area, (k * 255f).roundToInt()) else -1
            if (front) painter().drawFront(c) else painter().drawBack(c)
            if (n >= 0) c.restoreToCount(n)
        }

        override fun render(c: Canvas, now: Long, k: Float) {
            val n = if (k < 1f) c.saveLayerAlpha(area, (k * 255f).roundToInt()) else -1
            if (inlineBack) backPic?.let { c.drawPicture(it) }
            val p = painter()
            p.setLevel(0, level(lvl, ls))
            val sp = ls
            agitation[0] = if (sp != null) sp.velocity else 0f
            p.drawLive(c, agitation)
            val lim = limit
            if (lim != null && lim > 0.01f && lim < 0.99f) p.drawLimit(c, lim)
            if (inlineFront) frontPic?.let { c.drawPicture(it) }
            if (n >= 0) c.restoreToCount(n)
        }
    }

    /**
     * A built-in widget's graphic as a layer: a private instance on its own ticker, bound from the layer's
     * properties, ticked by this widget's frames and drawn through its object hook inside its own bounds.
     */
    private abstract inner class ObjectLayer(spec: CustomDoc.Spec) : Layer(spec, true) {
        protected val x = geo("x", 0f)
        protected val y = geo("y", 0f)
        protected val scale = geo("scale", 1f).coerceIn(0.05f, 8f)
        protected abstract val view: GlassWidgetView
        protected abstract fun bounds(out: RectF)
        protected abstract fun drawObject(c: Canvas, now: Long)
        /** Advances the object; true while what it draws for this layer still moves. */
        protected abstract fun move(now: Long, dt: Float): Boolean

        // the private ticker drives nothing: this widget's frames advance the object, so it stops with them
        protected fun <V : GlassWidgetView> detached(make: (GlassTicker) -> V): V {
            val own = GlassTicker()
            return make(own).also { own.remove(it); it.sizeK = hostedK() }
        }

        // its own resolution follows this widget's size and the layer's scale, no finer than a built-in ever draws
        private fun hostedK() = min(sizeK * scale, HOSTED_MAX_K)

        fun tick(now: Long, dt: Float): Boolean = move(now, dt) && visible

        fun syncEnv() {
            val v = view
            val k = hostedK()
            if (v.sizeK != k) v.sizeK = k
            if (v.stale != stale) v.stale = stale else v.envChanged()
        }

        override fun themeChanged() = view.themeChanged()

        override fun render(c: Canvas, now: Long, k: Float) {
            bounds(box)
            if (box.isEmpty) return
            val n = c.save()
            c.translate(x, y)
            c.scale(scale, scale)
            c.translate(-box.left, -box.top)
            c.clipRect(box)
            if (k < 1f) c.saveLayerAlpha(box, (k * 255f).roundToInt())
            drawObject(c, now)
            c.restoreToCount(n)
        }
    }

    private inner class CarLayer(spec: CustomDoc.Spec) : ObjectLayer(spec) {
        private val car = detached { GlassPowerView(context, env, it) }
        override val view: GlassWidgetView get() = car
        private val parts = partsOf(word("parts"))
        private val drive = when (word("drive")) { "fwd" -> GlassPowerView.Drive.FWD; "rwd" -> GlassPowerView.Drive.RWD; else -> GlassPowerView.Drive.AWD }
        private var last: GlassPowerView.State? = null

        override fun update(instant: Boolean, now: Long) {
            val phase = when (value("phase")) {
                "charging" -> GlassPowerView.Phase.CHARGING
                "driving" -> GlassPowerView.Phase.DRIVING
                else -> GlassPowerView.Phase.PARKED
            }
            // physical ranges, so no reading can push the borrowed drawing outside what it was built for;
            // the tyre band is the car's own, as on the built-in widget
            val st = GlassPowerView.State(
                phase = phase, kw = num("power")?.coerceIn(-MAX_KW, MAX_KW), drive = drive, soc = (num("soc") ?: 50f).coerceIn(0f, 100f),
                fl = bar(num("fl")), fr = bar(num("fr")), rl = bar(num("rl")), rr = bar(num("rr")),
                loBar = bar(signal("tyre_low_bar")) ?: 2.34f, hiBar = bar(signal("tyre_high_bar")) ?: 3.10f,
            )
            if (st == last && !forcing) return
            last = st
            car.bind(st, !instant, forcing)
            if (!instant) settle(now)
        }

        private fun bar(v: Float?) = v?.coerceIn(0f, MAX_BAR)

        override fun move(now: Long, dt: Float) = car.tickObject(now, dt, parts)
        override fun bounds(out: RectF) = car.objectBounds(parts, out)
        override fun drawObject(c: Canvas, now: Long) = car.drawObject(c, now, parts)
    }

    private inner class RoadLayer(spec: CustomDoc.Spec) : ObjectLayer(spec) {
        private val road = detached { GlassRoadSenseView(context, env, it) }
        override val view: GlassWidgetView get() = road
        private val region = word("region") ?: "eu"
        private val keepLeft = word("side") != "right"
        private var last: GlassRoadSenseView.State? = null

        override fun update(instant: Boolean, now: Long) {
            val hz = num("hazard")
            val kind = when (hz) {
                0f -> GlassRoadSenseView.Kind.BREAKER
                1f -> GlassRoadSenseView.Kind.POTHOLE
                3f -> GlassRoadSenseView.Kind.ROUGH
                else -> GlassRoadSenseView.Kind.UNKNOWN
            }
            val st = GlassRoadSenseView.State(
                active = true, feed = true, clear = !CustomExpr.truthy(value("ahead")), kind = kind,
                severity = (num("severity") ?: 1f).roundToInt().coerceIn(1, 3),
                distanceM = (num("distance") ?: 0f).coerceIn(0f, MAX_DISTANCE_M),
                cluster = (num("count") ?: 1f).roundToInt().coerceIn(1, 4),
                keepLeft = keepLeft, region = region,
            )
            if (st == last && !forcing) return
            last = st
            road.bind(st, !instant, forcing)
            if (!instant) settle(now)
        }

        override fun move(now: Long, dt: Float) = road.tickObject(now, dt)
        override fun bounds(out: RectF) = road.objectBounds(out)
        override fun drawObject(c: Canvas, now: Long) = road.drawObject(c, now)
    }

    companion object {
        private const val TAG = "GlassCustomView"
        private const val SPARK_MAX = 360
        private const val SPARK_GAP_MS = 900L
        private const val SETTLE_MS = 1200L
        private const val LOOP_FRAME_MS = 33L
        // the largest a built-in widget is resized to in arrange mode
        private const val HOSTED_MAX_K = 1.6f
        private const val MAX_KW = 2000f
        private const val MAX_BAR = 10f
        private const val MAX_DISTANCE_M = 100_000f
        private val GLOW_W = floatArrayOf(10f, 6f, 3f)
        private val GLOW_A = floatArrayOf(0.07f, 0.10f, 0.16f)
        private val POW10 = DoubleArray(10).also { var p = 1.0; for (i in it.indices) { it[i] = p; p *= 10.0 } }
        // indexed like CustomDoc.PARTS
        private val PART_BITS = intArrayOf(
            GlassPowerView.PART_BODY, GlassPowerView.PART_PACK, GlassPowerView.PART_MOTORS, GlassPowerView.PART_TYRES,
            GlassPowerView.PART_PRESSURES, GlassPowerView.PART_FLOW, GlassPowerView.PART_PORT,
        )

        private fun partsOf(list: String?): Int {
            val idx = list?.let { CustomDoc.partIndexes(it) } ?: return GlassPowerView.PARTS_ALL
            var m = 0
            for (i in idx) m = m or PART_BITS[i]
            return m
        }

        /** The theme colours a widget file may name ([CustomDoc.COLOR_TOKENS]); they follow night and day. */
        internal fun token(t: GlassTheme, name: String): Int? = when (name) {
            "accent" -> t.accent; "good" -> t.good; "warn" -> t.warn; "danger" -> t.danger
            "glowAccent" -> t.glowAccent; "glowGood" -> t.glowGood; "glowWarn" -> t.glowWarn; "glowDanger" -> t.glowDanger
            "fuel" -> t.fuel; "regen" -> t.regen; "regenText" -> t.regenText
            "text" -> t.text; "text2" -> t.text2; "text3" -> t.text3; "etch" -> t.etch
            "white" -> 0xFFFFFFFF.toInt(); "black" -> 0xFF000000.toInt()
            else -> null
        }
    }
}
