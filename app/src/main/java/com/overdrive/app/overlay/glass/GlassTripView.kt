package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Trip — distance and time on the left; on the right the efficiency in kWh per 100 km over a meter
 * banded like its word; along the bottom average and top speed, the energy used and the battery
 * from the start to now. Driving it shows this trip, parked the last one.
 */
@SuppressLint("ViewConstructor")
class GlassTripView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, 400f, 184f) {

    /** The trip's figures; a null one isn't known (yet). [score] is the trip's own driving score, never an average. */
    data class State(
        val km: Float, val minutes: Float, val whPerKm: Int?, val live: Boolean,
        val avgKmh: Float? = null, val topKmh: Int? = null, val kwh: Float? = null,
        val socStart: Int? = null, val socEnd: Int? = null,
        val cost: Float? = null, val currency: String? = null, val score: Int? = null,
        /** A hybrid's engine ran (null: not known), burning [fuelL] litres: the kWh figures then cover only part of the way. */
        val engine: Boolean? = false, val fuelL: Float? = null,
    )

    private var state = State(0f, 0f, null, true)
    private val km = Spring.number()
    private val meter = Spring.number()
    private val text = GlassText(context) { env.theme }
    private var shownTenths = Int.MIN_VALUE
    private var bound = false

    private val caption = GlassFonts.paint(context, 600, 11.5f, 0).apply { letterSpacing = 0.08f }
    private val effNum = GlassFonts.paint(context, 700, 28f, 0)
    private val effUnit = GlassFonts.paint(context, 500, 13f, 0)
    private val effWord = GlassFonts.paint(context, 600, 13f, 0).apply { textAlign = Paint.Align.RIGHT }
    private val cellNum = GlassFonts.paint(context, 600, 19f, 0)
    private val cellUnit = GlassFonts.paint(context, 500, 12f, 0)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()

    // set on bind, so drawing never formats or measures
    private var hasEff = false
    private var effText = DASH
    private var effUnitText = ""
    private var effUnitX = 0f
    private var wordText = ""
    private var wordColor = 0
    private var meterColor = 0
    private var knobShader: RadialGradient? = null
    private var dotX = 0f
    private val cellNums = Array(4) { DASH }
    private val cellUnits = Array(4) { "" }
    private val cellUnitX = FloatArray(4)
    private val cellK = FloatArray(4) { 1f }

    /** Unchanged data is a no-op; [force] re-applies it (theme or unit change). */
    fun bind(st: State, animate: Boolean, force: Boolean = false) {
        if (!force && bound && st == state) return
        // another trip (this one ending, or a new one starting) replaces the figures rather than counting to them
        val other = bound && (st.live != state.live || st.km + 0.3f < state.km)
        bound = true
        if (force) shownTenths = Int.MIN_VALUE
        state = st
        val instant = !animate || env.reduceMotion || other
        km.set(st.km, instant)
        val t = env.theme
        val miles = env.miles

        text.labelText = if (st.live) gs(R.string.glass_this_trip) else gs(R.string.glass_last_trip)
        dotX = X0 + text.label.measureText(text.labelText) + 9f
        text.statusLead = duration(st.minutes.roundToInt())
        text.statusText = extras(st)
        text.statusColor = t.text2

        val wh = st.whPerKm?.takeIf { it > 0 }
        hasEff = wh != null
        if (wh != null) {
            effText = String.format(Locale.US, "%.1f", GlassUnits.consumptionValue(wh, miles))
            val (w, wc) = when {
                // petrol covered part of the way (or may have), so the electric figure alone can't be judged
                st.engine == true -> gs(R.string.glass_eff_engine) to t.text2
                st.engine == null -> gs(R.string.glass_eff_hybrid) to t.text2
                wh <= 130 -> gs(R.string.glass_eff_efficient) to t.good
                wh <= 170 -> gs(R.string.glass_eff_normal) to t.accent
                wh <= 220 -> gs(R.string.glass_eff_heavy) to t.warn
                else -> gs(R.string.glass_eff_very_heavy) to t.danger
            }
            wordText = w; wordColor = wc
            val c = if (st.engine != false) t.text2 else effColor(t, wh.toFloat())
            if (c != meterColor || knobShader == null) {
                meterColor = c
                knobShader = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(c, 0.6f), GlassColor.alpha(c, 0.2f), GlassColor.clear(c)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            }
            meter.set((wh / MAX_WH).coerceIn(0f, 1f), instant)
        } else {
            effText = DASH
            wordText = if (st.live) gs(R.string.glass_measuring) else ""
            wordColor = t.text2
            // the first figure sweeps in from the left end
            meter.snap(0f)
        }
        effUnitText = GlassUnits.consumptionUnit(miles)
        effUnitX = RX0 + effNum.measureText(effText) + 5f

        val su = " " + GlassUnits.speedUnit(miles)
        cell(0, st.avgKmh?.takeIf { it > 0f }?.let { GlassUnits.speed(it, miles).roundToInt().toString() }, su)
        cell(1, st.topKmh?.takeIf { it > 0 }?.let { GlassUnits.speed(it.toFloat(), miles).roundToInt().toString() }, su)
        cell(2, st.kwh?.takeIf { it > 0f }?.let { String.format(Locale.US, if (it < 100f) "%.1f" else "%.0f", it) }, " kWh")
        val from = st.socStart?.takeIf { it > 0 }
        val to = st.socEnd?.takeIf { it > 0 }
        cell(3, when { from != null && to != null -> "$from→$to"; to != null -> "$to"; else -> null }, "%")

        updateKm()
        requestFrames(); invalidate()
    }

    private fun cell(i: Int, n: String?, unit: String) {
        cellNums[i] = n ?: DASH
        cellUnits[i] = if (n == null) "" else unit
        val nw = cellNum.measureText(cellNums[i])
        cellUnitX[i] = cellX(i) + nw + 1f
        // a figure too wide for its cell ("100→100%") is drawn a little smaller, never over the next one
        val w = nw + if (cellUnits[i].isEmpty()) 0f else 1f + cellUnit.measureText(cellUnits[i])
        val room = (if (i == 3) X1 else X0 + (X1 - X0) / 4f * (i + 1) - 6f) - cellX(i)
        cellK[i] = if (w > room) room / w else 1f
    }

    // fuel burned, the cost and the trip's score after the time, as many as fit beside the efficiency column
    private fun extras(st: State): String {
        val parts = ArrayList<String>(3)
        st.fuelL?.takeIf { it > 0f }?.let { parts.add(gs(R.string.glass_trip_fuel, String.format(Locale.US, "%.1f", it))) }
        val cur = st.currency
        st.cost?.takeIf { it > 0f && !cur.isNullOrEmpty() }?.let {
            parts.add(cur + if (it < 100f) String.format(Locale.US, "%.1f", it) else it.roundToInt().toString())
        }
        st.score?.takeIf { it > 0 }?.let { parts.add(gs(R.string.glass_trip_score, it)) }
        while (parts.isNotEmpty() && text.status.measureText(text.statusLead + SEP + parts.joinToString(SEP)) > RX0 - X0 - 14f) parts.removeAt(parts.size - 1)
        return parts.joinToString(SEP)
    }

    // formats only when the shown value changes; one decimal below 100, whole units above, with keys that can't collide
    private fun updateKm() {
        val v = max(0f, GlassUnits.dist(km.value, env.miles))
        val decimal = v < 100f
        val key = if (decimal) (v * 10f).roundToInt() else -v.roundToInt() - 1
        if (key == shownTenths) return
        shownTenths = key
        text.setValue(if (decimal) "${key / 10}.${key % 10}" else v.roundToInt().toString(), GlassUnits.distUnit(env.miles))
    }

    private fun duration(m: Int) = when { m < 1 -> gs(R.string.glass_dur_lt_min); m < 60 -> gs(R.string.glass_dur_min, m); m % 60 == 0 -> gs(R.string.glass_dur_h, m / 60); else -> gs(R.string.glass_dur_h_min, m / 60, m % 60) }

    override fun onThemeChanged() { meterColor = 0; knobShader = null; if (bound) bind(state, false, force = true) }

    override fun animate(nowMs: Long, dt: Float): Boolean {
        val a = km.step(dt); val b = meter.step(dt)
        updateKm()
        return a || b
    }

    /** The rule, the cells' dividers and captions, and the meter's faint bands. */
    override fun drawBack(c: Canvas) {
        val t = env.theme
        val ink = t.ink
        val a = if (t.night) 0.16f else 0.18f
        p.shade(LinearGradient(X0, 0f, X1, 0f, intArrayOf(GlassColor.clear(ink), GlassColor.alpha(ink, a), GlassColor.alpha(ink, a), GlassColor.clear(ink)), floatArrayOf(0f, 0.1f, 0.9f, 1f), Shader.TileMode.CLAMP))
        c.drawRect(X0, RULE_Y - 0.5f, X1, RULE_Y + 0.5f, p)
        p.shader = null
        s.strokeWidth = 1f; s.color = GlassColor.alpha(ink, if (t.night) 0.11f else 0.12f)
        val cw = (X1 - X0) / 4f
        for (i in 1 until 4) c.drawLine(X0 + cw * i, CELL_TOP, X0 + cw * i, CELL_BOT, s)
        caption.color = t.text2
        for (i in 0 until 4) c.drawText(CAPTIONS[i], cellX(i), CAPTION_Y, caption)
        c.drawText(gs(R.string.glass_cap_efficiency), RX0, EFF_CAPTION_Y, caption)
        bands(c, t, if (t.night) 0.22f else 0.2f)
    }

    // the meter's four bands, on the efficiency words' boundaries
    private fun bands(c: Canvas, t: GlassTheme, alpha: Float) {
        var from = 0f
        for (i in BANDS.indices) {
            val to = BANDS[i]
            r.set(meterX(from) + if (i > 0) BAND_GAP / 2f else 0f, METER_Y, meterX(to) - if (i < BANDS.size - 1) BAND_GAP / 2f else 0f, METER_Y + METER_H)
            p.shader = null
            p.color = GlassColor.alpha(when (i) { 0 -> t.glowGood; 1 -> t.glowAccent; 2 -> t.glowWarn; else -> t.glowDanger }, alpha)
            c.drawRoundRect(r, METER_H / 2f, METER_H / 2f, p)
            from = to
        }
    }

    override fun drawLive(c: Canvas, nowMs: Long) {
        val t = env.theme
        text.draw(c, X0, 0f, RULE_Y)
        // a live trip's label carries a lit dot
        if (state.live) {
            p.shader = null
            p.color = GlassColor.alpha(t.glowAccent, 0.22f); c.drawCircle(dotX, LABEL_MID_Y, 6f, p)
            p.color = t.glowAccent; c.drawCircle(dotX, LABEL_MID_Y, 3.2f, p)
        }

        effNum.color = if (hasEff) t.text else t.text3
        c.drawText(effText, RX0, EFF_Y, effNum)
        effUnit.color = t.text2
        c.drawText(effUnitText, effUnitX, EFF_Y, effUnit)
        if (wordText.isNotEmpty()) { effWord.color = wordColor; c.drawText(wordText, RX1, EFF_CAPTION_Y, effWord) }
        val f = meter.value
        if (hasEff && f > 0.001f) {
            val x = meterX(f * MAX_WH)
            val cy = METER_Y + METER_H / 2f
            // with the engine in the mix only the knob shows, in no band's colour
            if (state.engine == false) {
                c.save()
                c.clipRect(RX0 - 1f, METER_Y - 2f, x, METER_Y + METER_H + 2f)
                bands(c, t, 1f)
                c.restore()
                knobShader?.let { c.unitOval(p, it, x, cy, 13f, 13f) }
            }
            p.shader = null; p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(x, cy, 4.8f, p)
            s.strokeWidth = 1.8f; s.color = meterColor
            c.drawCircle(x, cy, 4.8f, s)
        }

        for (i in 0 until 4) {
            val n = cellNums[i]
            val k = cellK[i]
            if (k < 1f) { c.save(); c.scale(k, k, cellX(i), CELL_Y) }
            cellNum.color = if (cellUnits[i].isEmpty()) t.text3 else t.text
            c.drawText(n, cellX(i), CELL_Y, cellNum)
            if (cellUnits[i].isNotEmpty()) { cellUnit.color = t.text2; c.drawText(cellUnits[i], cellUnitX[i], CELL_Y, cellUnit) }
            if (k < 1f) c.restore()
        }
    }

    override fun drawFront(c: Canvas) {}

    private fun cellX(i: Int) = X0 + (X1 - X0) / 4f * i + if (i == 0) 0f else CELL_PAD
    private fun meterX(wh: Float) = RX0 + (RX1 - RX0) * (wh / MAX_WH).coerceIn(0f, 1f)

    companion object {
        private const val X0 = 22f
        private const val X1 = 378f
        // the efficiency column, its rows on the text column's baselines: label, value, status
        private const val RX0 = 222f
        private const val RX1 = 378f
        private const val EFF_CAPTION_Y = 26f
        private const val EFF_Y = 72f
        private const val METER_Y = 91f
        private const val METER_H = 7f
        private const val BAND_GAP = 2.5f
        private const val MAX_WH = 300f
        private val BANDS = floatArrayOf(130f, 170f, 220f, 300f)
        // the rule and the cells under it
        private const val RULE_Y = 114f
        private const val CELL_TOP = 126f
        private const val CELL_BOT = 168f
        private const val CAPTION_Y = 139f
        private const val CELL_Y = 163f
        private const val CELL_PAD = 12f
        private val CAPTIONS get() = arrayOf(gs(R.string.glass_cap_avg_speed), gs(R.string.glass_cap_top_speed), gs(R.string.glass_cap_energy), gs(R.string.glass_cap_battery))
        // the middle of the text column's label line, centred above the rule like the column
        private val LABEL_MID_Y = (RULE_Y - (GlassText.LABEL_H + GlassText.GAP * 2 + 38f + GlassText.STATUS_H)) / 2f + GlassText.LABEL_H / 2f
        private const val DASH = "—"
        private const val SEP = " · "
        private val EFF_X = floatArrayOf(0f, 127f, 133f, 167f, 173f, 217f, 223f)

        /** Colour steps sit on the same boundaries as the efficiency words. */
        fun effColor(t: GlassTheme, wh: Float) = GlassColor.rampHue(EFF_X,
            intArrayOf(t.glowGood, t.glowGood, t.glowAccent, t.glowAccent, t.glowWarn, t.glowWarn, t.glowDanger), wh)
    }
}
