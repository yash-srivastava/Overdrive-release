package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live drive — the last five minutes as a glowing ribbon: power drawn rises above the line in the
 * drive hue, regen falls below it in the regen hue, and speed runs over it as a fine line, with a
 * live head at "now". A key above names each trace with its latest reading, dashed lines mark round
 * kW figures and the time runs from five minutes ago to now. Beside it the trip's distance and time;
 * under it efficiency, regen share and top speed. Samples come about once a second.
 */
@SuppressLint("ViewConstructor")
class GlassDriveView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, 520f, 184f) {

    /** The trip's totals; null fields aren't known yet. */
    data class Trip(val live: Boolean, val km: Float, val minutes: Int, val whPerKm: Int?, val topKmh: Int?, val regenPct: Int?)

    private var trip = Trip(false, 0f, 0, null, null, null)
    private var bound = false

    // samples, oldest first, in a ring: uptime ms, pack kW (+ drawing, − regen), km/h; NaN = not reported
    private val tA = LongArray(CAP)
    private val kwA = FloatArray(CAP)
    private val kmhA = FloatArray(CAP)
    private var next = 0
    private var count = 0
    private val xs = FloatArray(CAP)
    private val ys = FloatArray(CAP)
    private val ms = FloatArray(CAP)
    private val tan = FloatArray(CAP)
    // each power sample's ring slot and curve slope, so the live bead rides the same curve as the ribbon
    private val idxs = IntArray(CAP)
    private val kwTan = FloatArray(CAP)

    // the ribbon in source units (x = ms before the newest sample, y = kW or km/h), mapped per frame
    private val fillSrc = Path()
    private val lineSrc = Path()
    private val speedSrc = Path()
    private val speedFillSrc = Path()
    private val fillDst = Path()
    private val speedFillDst = Path()
    private val pos = FloatArray(CAP)
    private val neg = FloatArray(CAP)
    private val lineDst = Path()
    private val speedDst = Path()
    private val mx = Matrix()
    private var newest = 0L

    // vertical scales ease when the peak changes, so the ribbon breathes instead of jumping
    private val kwScale = Spring.shape(1f)
    private val kmhScale = Spring.shape(1f)

    private val text = GlassText(context) { env.theme }
    private val km = Spring.number()
    private var shownKm = Int.MIN_VALUE
    private val legendLabel = GlassFonts.paint(context, 500, 12f, 0)
    private val legendValue = GlassFonts.paint(context, 700, 13f, 0)
    private val axis = GlassFonts.paint(context, 500, 10f, 0)
    private val gridDash = android.graphics.DashPathEffect(floatArrayOf(3f, 4f), 0f)
    // the key's figures, set per sample
    private var legendPower = ""
    private var legendRegen = ""
    private var legendSpeed = ""
    private var lastSpeedShown = Int.MAX_VALUE
    private var lastSpeedMiles = false
    private var gridUp = -1f
    private var gridDown = -1f
    private var gridUpText = ""
    private var gridDownText = ""
    private val chipText = GlassFonts.paint(context, 600, 13f, 0)
    private val note = GlassFonts.paint(context, 500, 13f, 0).apply { textAlign = Paint.Align.CENTER }
    private var kwText = ""
    private var kmhText = ""
    private var lastKw = Float.NaN
    private val chips = ArrayList<String>(3)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val speed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val speedFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val fm = Paint.FontMetrics()
    private var shaderNight: Boolean? = null
    private lateinit var headGlowUp: RadialGradient
    private lateinit var headGlowDown: RadialGradient

    private val streaming get() = trip.live && count > 0 && android.os.SystemClock.uptimeMillis() - newest < STALE_MS

    // only the scales easing need every frame; the distance label changes at most once per 0.1 km
    override val minFrameMs: Long get() = if (kwScale.moving || kmhScale.moving) 0L else FRAME_MS

    /** Unchanged data is a no-op; [force] re-applies it (theme change). */
    fun bind(t: Trip, animate: Boolean, force: Boolean = false) {
        if (!force && bound && t == trip) return
        val newTrip = bound && t.live && (!trip.live || t.km + 0.3f < trip.km)
        bound = true
        trip = t
        if (newTrip) clear()
        val instant = !animate || env.reduceMotion
        // a new trip starts from its own distance, not counting down from the last one
        km.set(t.km, instant || newTrip)
        shownKm = Int.MIN_VALUE
        text.labelText = if (t.live) gs(R.string.glass_this_trip) else gs(R.string.glass_last_trip)
        text.statusText = duration(t.minutes)
        text.statusColor = 0
        updateKm()
        // the chips are the only baked part that follows the trip: re-bake only when they change
        if (t.whPerKm != chipWh || t.regenPct != chipRegen || t.topKmh != chipTop || force) {
            chipWh = t.whPerKm; chipRegen = t.regenPct; chipTop = t.topKmh
            chips.clear()
            t.whPerKm?.let { chips.add(GlassUnits.consumption(it, env.miles)) }
            t.regenPct?.let { chips.add(gs(R.string.glass_chip_regen, it)) }
            t.topKmh?.let { if (it > 0) chips.add(gs(R.string.glass_chip_top, GlassUnits.speed(it.toFloat(), env.miles).roundToInt(), GlassUnits.speedUnit(env.miles))) }
            layersChanged()
        }
        requestFrames()
        invalidate()
    }

    private var chipWh: Int? = null
    private var chipRegen: Int? = null
    private var chipTop: Int? = null

    /** One reading, stamped with [uptimeMs]. Drawn [LAG_MS] late so the ribbon always reaches "now". */
    fun addSample(uptimeMs: Long, kw: Float, kmh: Float) {
        if (kw.isNaN() && kmh.isNaN()) return
        if (count > 0 && uptimeMs <= newest) return
        tA[next] = uptimeMs; kwA[next] = kw; kmhA[next] = kmh
        next = (next + 1) % CAP
        if (count < CAP) count++
        newest = uptimeMs
        // a reading the car didn't give blanks its figure, like the gap it leaves in the trace
        if (kw.isNaN()) { kwText = ""; lastKw = Float.NaN; legendPower = ""; legendRegen = "" }
        else if (kw != lastKw) {
            lastKw = kw
            val v = abs(kw)
            // regen reads as a negative figure as well as in its own hue
            val sign = if (kw <= -0.05f) "−" else ""
            val num = if (v < 10f) String.format(Locale.US, "%.1f", v) else v.roundToInt().toString()
            kwText = sign + num
            // the key carries the figure under whichever trace it belongs to, without a sign
            legendPower = if (sign.isEmpty()) "$num kW" else ""
            legendRegen = if (sign.isEmpty()) "" else "$num kW"
        }
        val sp = if (kmh.isNaN()) Int.MIN_VALUE else GlassUnits.speed(kmh, env.miles).roundToInt().coerceAtLeast(0)
        if (sp != lastSpeedShown || env.miles != lastSpeedMiles) {
            lastSpeedShown = sp; lastSpeedMiles = env.miles
            kmhText = if (sp == Int.MIN_VALUE) "" else sp.toString()
            legendSpeed = if (sp == Int.MIN_VALUE) "" else "$sp ${GlassUnits.speedUnit(env.miles)}"
        }
        rebuild()
        requestFrames()
        invalidate()
        // when the samples stop the trace freezes and the live readouts go; draw that moment
        removeCallbacks(streamEnded)
        postDelayed(streamEnded, STALE_MS + 50L)
    }

    private val streamEnded = Runnable { invalidate() }

    private fun clear() {
        count = 0; next = 0; newest = 0L; lastKw = Float.NaN
        kwText = ""; kmhText = ""
        legendPower = ""; legendRegen = ""; legendSpeed = ""; lastSpeedShown = Int.MAX_VALUE
        fillSrc.rewind(); lineSrc.rewind(); speedSrc.rewind(); speedFillSrc.rewind()
    }

    private fun at(i: Int) = (next - count + i + CAP) % CAP

    // rebuilds the source paths from the samples in the window: a monotone curve through each run
    // (a gap or a missing reading starts a new run), so the trace never overshoots the data
    private fun rebuild() {
        fillSrc.rewind(); lineSrc.rewind(); speedSrc.rewind(); speedFillSrc.rewind()
        var nPos = 0; var nNeg = 0; var peakKmh = 60f
        val from = newest - WINDOW_MS - LAG_MS - 2000L
        var n = 0
        var lastT = Long.MIN_VALUE
        // power
        for (i in 0 until count) {
            val j = at(i)
            val t = tA[j]
            if (t < from) continue
            val kw = kwA[j]
            if (kw.isNaN() || (n > 0 && t - lastT > GAP_MS)) { flush(n, true); n = 0 }
            if (!kw.isNaN()) {
                xs[n] = (t - newest).toFloat(); ys[n] = kw; idxs[n] = j; n++
                if (kw > 0f) pos[nPos++] = kw else if (kw < 0f) neg[nNeg++] = -kw
            }
            lastT = t
        }
        flush(n, true)
        // speed
        n = 0; lastT = Long.MIN_VALUE
        for (i in 0 until count) {
            val j = at(i)
            val t = tA[j]
            if (t < from) continue
            val v = kmhA[j]
            if (v.isNaN() || (n > 0 && t - lastT > GAP_MS)) { flush(n, false); n = 0 }
            if (!v.isNaN()) {
                xs[n] = (t - newest).toFloat(); ys[n] = max(0f, v); n++
                if (v > peakKmh) peakKmh = v
            }
            lastT = t
        }
        flush(n, false)
        // scaled to the bulk of the readings, not the odd spike: a hard launch clips at the top instead
        // of flattening five minutes of ordinary driving
        val drive = max(30f, percentile(pos, nPos) * 1.3f)
        val regen = max(15f, percentile(neg, nNeg) * 1.3f)
        val instant = env.reduceMotion
        kwScale.set(min((Y_ZERO - Y_TOP) / drive, (Y_BOT - Y_ZERO) / regen), instant)
        kmhScale.set((Y_BOT - Y_TOP - 6f) / (peakKmh * 1.12f), instant)
    }

    private fun percentile(a: FloatArray, n: Int): Float {
        if (n == 0) return 0f
        java.util.Arrays.sort(a, 0, n)
        return a[(n * 0.95f).toInt().coerceAtMost(n - 1)]
    }

    // Fritsch–Carlson tangents, then one cubic per span
    private fun flush(n: Int, power: Boolean) {
        if (n <= 0) return
        val target = if (power) lineSrc else speedSrc
        val area = if (power) fillSrc else speedFillSrc
        if (power && n == 1) kwTan[idxs[0]] = 0f
        if (n == 1) {
            target.moveTo(xs[0] - 500f, ys[0]); target.lineTo(xs[0], ys[0])
            area.moveTo(xs[0] - 500f, 0f); area.lineTo(xs[0] - 500f, ys[0]); area.lineTo(xs[0], ys[0]); area.lineTo(xs[0], 0f); area.close()
            return
        }
        tangents(n)
        if (power) for (i in 0 until n) kwTan[idxs[i]] = tan[i]
        target.moveTo(xs[0], ys[0])
        area.moveTo(xs[0], 0f); area.lineTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val h = xs[i + 1] - xs[i]
            val c1x = xs[i] + h / 3f; val c1y = ys[i] + tan[i] * h / 3f
            val c2x = xs[i + 1] - h / 3f; val c2y = ys[i + 1] - tan[i + 1] * h / 3f
            target.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
            area.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
        }
        area.lineTo(xs[n - 1], 0f); area.close()
    }

    private fun tangents(n: Int) {
        for (i in 0 until n - 1) ms[i] = (ys[i + 1] - ys[i]) / max(1f, xs[i + 1] - xs[i])
        tan[0] = ms[0]; tan[n - 1] = ms[n - 2]
        for (i in 1 until n - 1) tan[i] = if (ms[i - 1] * ms[i] <= 0f) 0f else (ms[i - 1] + ms[i]) / 2f
        for (i in 0 until n - 1) {
            if (ms[i] == 0f) { tan[i] = 0f; tan[i + 1] = 0f; continue }
            val a = tan[i] / ms[i]; val b = tan[i + 1] / ms[i]
            val h = a * a + b * b
            if (h > 9f) { val k = 3f / sqrt(h); tan[i] = k * a * ms[i]; tan[i + 1] = k * b * ms[i] }
        }
    }

    private fun updateKm() {
        val v = max(0f, GlassUnits.dist(km.value, env.miles))
        val key = if (v < 100f) (v * 10f).roundToInt() else -v.roundToInt() - 1
        if (key == shownKm) return
        shownKm = key
        text.setValue(if (key >= 0) "${key / 10}.${key % 10}" else v.roundToInt().toString(), GlassUnits.distUnit(env.miles))
    }

    private fun duration(min: Int) = if (min < 60) gs(R.string.glass_dur_min, min) else if (min % 60 == 0) gs(R.string.glass_dur_h, min / 60) else gs(R.string.glass_dur_h_min, min / 60, min % 60)

    override fun animate(nowMs: Long, dt: Float): Boolean {
        var busy = false
        if (kwScale.step(dt)) busy = true
        if (kmhScale.step(dt)) busy = true
        if (km.step(dt)) busy = true
        updateKm()
        // the scroll and the head's breath are the only loop; reduce-motion moves a step per sample
        if (streaming && env.allows(GlassEnv.Loop.DRIVE, stale)) busy = true
        return busy
    }

    override fun onThemeChanged() { shaderNight = null; if (bound) bind(trip, false, force = true) }

    // ── drawing ──────────────────────────────────────────────────────────────

    private fun ensureShaders(t: GlassTheme) {
        if (shaderNight == t.night) return
        shaderNight = t.night
        val zf = (Y_ZERO - Y_TOP) / (Y_BOT - Y_TOP)
        val up = t.glowAccent; val down = t.regen
        // the past fades into the glass on the left
        fun faded(sh: Shader) = ComposeShader(sh, LinearGradient(X0, 0f, X0 + FADE, 0f, GlassColor.clear(0xFFFFFFFF.toInt()), 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP), PorterDuff.Mode.DST_IN)
        fill.shader = faded(LinearGradient(0f, Y_TOP, 0f, Y_BOT,
            intArrayOf(GlassColor.alpha(up, if (t.night) 0.62f else 0.46f), GlassColor.alpha(up, 0.1f), GlassColor.alpha(down, 0.1f), GlassColor.alpha(down, if (t.night) 0.6f else 0.44f)),
            floatArrayOf(0f, zf, zf, 1f), Shader.TileMode.CLAMP))
        line.shader = faded(LinearGradient(0f, Y_TOP, 0f, Y_BOT, intArrayOf(GlassColor.lighten(up, 0.2f), GlassColor.lighten(up, 0.2f), GlassColor.lighten(down, 0.2f), GlassColor.lighten(down, 0.2f)),
            floatArrayOf(0f, zf, zf, 1f), Shader.TileMode.CLAMP))
        glow.shader = faded(LinearGradient(0f, Y_TOP, 0f, Y_BOT, intArrayOf(GlassColor.alpha(up, 0.24f), GlassColor.alpha(up, 0.24f), GlassColor.alpha(down, 0.24f), GlassColor.alpha(down, 0.24f)),
            floatArrayOf(0f, zf, zf, 1f), Shader.TileMode.CLAMP))
        // speed is the terrain behind the power: a faint silhouette with a fine edge
        speed.shader = faded(LinearGradient(0f, 0f, 1f, 0f, GlassColor.alpha(t.text, if (t.night) 0.34f else 0.3f), GlassColor.alpha(t.text, if (t.night) 0.34f else 0.3f), Shader.TileMode.CLAMP))
        speedFill.shader = faded(LinearGradient(0f, Y_TOP, 0f, Y_BOT, GlassColor.alpha(t.text, if (t.night) 0.1f else 0.08f), GlassColor.clear(t.text), Shader.TileMode.CLAMP))
        fun head(c: Int) = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(0xFFFFFFFF.toInt(), 0.95f), GlassColor.alpha(c, 0.55f), GlassColor.clear(c)), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        headGlowUp = head(up); headGlowDown = head(down)
    }

    /** The zero line and the frame of the chart; the chips are baked in front. */
    override fun drawBack(c: Canvas) {
        val t = env.theme
        s.shader = null; s.pathEffect = null; s.strokeWidth = 1f
        s.color = t.etch
        c.drawLine(X0, Y_ZERO, X1, Y_ZERO, s)
        s.color = GlassColor.alpha(t.ink, if (t.night) 0.06f else 0.07f)
        c.drawLine(X0, Y_BOT, X1, Y_BOT, s)
    }

    override fun drawLive(c: Canvas, nowMs: Long) {
        val t = env.theme
        ensureShaders(t)
        text.draw(c, 26f, 0f, TEXT_BOTTOM)
        drawLegend(c, t)
        if (count == 0) {
            note.color = t.text3
            c.drawText(gs(R.string.glass_drive_trace_note), (X0 + X1) / 2f, (Y_TOP + Y_ZERO) / 2f + 5f, note)
            return
        }
        drawGrid(c, t)
        drawTimeAxis(c, t)
        val live = streaming && !env.reduceMotion
        // a late or last sample holds the scroll a lag past it, so the end of a drive doesn't snap back far
        val dn = if (live) min(nowMs - LAG_MS, newest + LAG_MS) else newest
        val pxPerMs = (X1 - X0) / WINDOW_MS.toFloat()
        val xOff = X1 + (newest - dn) * pxPerMs
        c.save()
        c.clipRect(X0, Y_TOP - 3f, X1 + 1f, Y_BOT + 3f)
        mx.setScale(pxPerMs, -kmhScale.value); mx.postTranslate(xOff, Y_BOT)
        speedFillSrc.transform(mx, speedFillDst)
        c.drawPath(speedFillDst, speedFill)
        speedSrc.transform(mx, speedDst)
        speed.strokeWidth = 1.2f
        c.drawPath(speedDst, speed)
        mx.setScale(pxPerMs, -kwScale.value); mx.postTranslate(xOff, Y_ZERO)
        fillSrc.transform(mx, fillDst)
        c.drawPath(fillDst, fill)
        lineSrc.transform(mx, lineDst)
        glow.strokeWidth = 6f
        c.drawPath(lineDst, glow)
        line.strokeWidth = 2f
        c.drawPath(lineDst, line)
        c.restore()
        drawHead(c, t, nowMs, dn, live)
    }

    // "now": a faint line and a glowing bead on the power curve, breathing while the car drives
    private fun drawHead(c: Canvas, t: GlassTheme, nowMs: Long, dn: Long, live: Boolean) {
        val kw = valueAt(dn, kwA)
        s.shader = null; s.pathEffect = null; s.strokeWidth = 1f
        s.color = GlassColor.alpha(t.ink, if (t.night) 0.14f else 0.16f)
        c.drawLine(X1, Y_TOP, X1, Y_BOT, s)
        if (kw.isNaN()) return
        val y = (Y_ZERO - kw * kwScale.value).coerceIn(Y_TOP - 6f, Y_BOT + 6f)
        val breath = if (live && env.allows(GlassEnv.Loop.DRIVE, stale)) 0.5f + 0.5f * sin(nowMs / 1600.0 * 2.0 * Math.PI).toFloat() else 0.6f
        c.unitOval(p, if (kw < 0f) headGlowDown else headGlowUp, X1, y, 11f + 5f * breath, 11f + 5f * breath, (180 + 60 * breath).roundToInt())
        p.shader = null; p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(X1, y, 3.2f, p)
    }

    // power at [tMs] on the ribbon's own curve (Hermite with the stored slopes); NaN across a gap
    private fun valueAt(tMs: Long, arr: FloatArray): Float {
        if (count == 0) return Float.NaN
        var later = -1
        for (i in count - 1 downTo 0) {
            val j = at(i)
            if (tA[j] <= tMs) {
                val v0 = arr[j]
                if (later < 0) return v0
                val v1 = arr[later]
                if (v0.isNaN() || v1.isNaN()) return if (v0.isNaN()) v1 else v0
                val h = max(1L, tA[later] - tA[j]).toFloat()
                if (h > GAP_MS) return Float.NaN
                val u = ((tMs - tA[j]) / h).coerceIn(0f, 1f)
                val u2 = u * u; val u3 = u2 * u
                return (2f * u3 - 3f * u2 + 1f) * v0 + (u3 - 2f * u2 + u) * h * kwTan[j] +
                    (-2f * u3 + 3f * u2) * v1 + (u3 - u2) * h * kwTan[later]
            }
            later = j
        }
        return arr[at(0)]
    }

    // the key above the chart, each series with its latest reading: power drawn, regen, speed
    private fun drawLegend(c: Canvas, t: GlassTheme) {
        val live = streaming
        val base = 30f
        var x = X0
        x = legendItem(c, x, base, t.glowAccent, false, gs(R.string.glass_power), if (live) legendPower else "", t.accent)
        x = legendItem(c, x, base, t.regen, false, gs(R.string.glass_regen), if (live) legendRegen else "", t.regenText)
        legendItem(c, x, base, GlassColor.alpha(t.text, 0.62f), true, gs(R.string.glass_speed), if (live) legendSpeed else "", t.text)
    }

    private fun legendItem(c: Canvas, x0: Float, base: Float, swatch: Int, line: Boolean, label: String, value: String, valueColor: Int): Float {
        var x = x0
        if (line) {
            s.shader = null; s.pathEffect = null; s.color = swatch; s.strokeWidth = 1.6f; s.strokeCap = Paint.Cap.ROUND
            c.drawLine(x, base - 4.5f, x + 10f, base - 4.5f, s)
        } else {
            p.shader = null; p.color = swatch
            r.set(x + 1f, base - 9f, x + 9f, base - 1f)
            c.drawRoundRect(r, 2f, 2f, p)
        }
        x += 15f
        legendLabel.color = env.theme.text2
        c.drawText(label, x, base, legendLabel)
        x += legendLabel.measureText(label)
        if (value.isNotEmpty()) {
            x += 5f
            legendValue.color = valueColor
            c.drawText(value, x, base, legendValue)
            x += legendValue.measureText(value)
        }
        return x + 16f
    }

    // a gridline at a round figure each side of zero, labelled at the chart's faded left edge
    private fun drawGrid(c: Canvas, t: GlassTheme) {
        val k = kwScale.value
        if (k <= 0f) return
        val up = nice((Y_ZERO - Y_TOP) / k)
        val down = nice((Y_BOT - Y_ZERO) / k)
        if (up != gridUp) { gridUp = up; gridUpText = "${up.roundToInt()} kW" }
        if (down != gridDown) { gridDown = down; gridDownText = "−${down.roundToInt()} kW" }
        s.shader = null; s.strokeWidth = 1f; s.pathEffect = gridDash
        s.color = GlassColor.alpha(t.ink, if (t.night) 0.1f else 0.11f)
        axis.color = t.text3
        if (up > 0f) {
            val y = Y_ZERO - up * k
            c.drawLine(X0, y, X1, y, s)
            c.drawText(gridUpText, X0 + 2f, y - 3f, axis)
        }
        if (down > 0f) {
            val y = Y_ZERO + down * k
            if (y - Y_ZERO > 12f) {
                c.drawLine(X0, y, X1, y, s)
                c.drawText(gridDownText, X0 + 2f, y - 3f, axis)
            }
        }
        s.pathEffect = null
        c.drawText("0", X0 + 2f, Y_ZERO - 3f, axis)
    }

    private fun nice(max: Float): Float {
        for (i in NICE.indices.reversed()) if (NICE[i] <= max * 0.9f) return NICE[i]
        return 0f
    }

    private fun drawTimeAxis(c: Canvas, t: GlassTheme) {
        axis.color = t.text3
        val y = Y_BOT + 11f
        c.drawText("5 min ago", X0, y, axis)
        val nowLabel = gs(R.string.glass_now)
        val w = axis.measureText(nowLabel)
        c.drawText(nowLabel, X1 - w, y, axis)
    }

    /** Efficiency, regen share and top speed as small glass chips under the chart. */
    override fun drawFront(c: Canvas) {
        val t = env.theme
        var x = CHIP_X
        chipText.getFontMetrics(fm)
        for (label in chips) {
            val w = chipText.measureText(label) + 22f
            if (x + w > X1 + 1f) break
            r.set(x, CHIP_Y, x + w, CHIP_Y + CHIP_H)
            p.shader = null
            p.color = if (t.night) GlassColor.alpha(t.glassBot, 0.42f) else 0x8CFFFFFF.toInt()
            c.drawRoundRect(r, CHIP_H / 2f, CHIP_H / 2f, p)
            r.inset(0.5f, 0.5f)
            s.shader = null; s.pathEffect = null; s.strokeWidth = 1f
            s.color = if (t.night) 0x24FFFFFF else 0x290B281E
            c.drawRoundRect(r, CHIP_H / 2f - 0.5f, CHIP_H / 2f - 0.5f, s)
            chipText.color = t.text2
            c.drawText(label, x + 11f, CHIP_Y + CHIP_H / 2f - (fm.ascent + fm.descent) / 2f, chipText)
            x += w + 8f
        }
    }

    companion object {
        private const val CAP = 720
        private const val WINDOW_MS = 300_000L
        private const val LAG_MS = 1500L
        private const val GAP_MS = 6000L
        private const val STALE_MS = 8000L
        private const val FRAME_MS = 50L
        private const val X0 = 204f
        private const val X1 = 494f
        private const val Y_TOP = 46f
        private const val Y_BOT = 134f
        private const val Y_ZERO = 106f
        private const val FADE = 56f
        private const val CHIP_X = 26f
        private const val TEXT_BOTTOM = 142f
        private const val CHIP_Y = 148f
        private const val CHIP_H = 24f
        private val NICE = floatArrayOf(5f, 10f, 20f, 25f, 50f, 75f, 100f, 150f, 200f, 300f)
    }
}
