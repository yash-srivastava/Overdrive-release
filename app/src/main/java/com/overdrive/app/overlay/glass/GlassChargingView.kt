package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Charging — the same cell with the charge limit etched round it and light rising into the liquid. */
@SuppressLint("ViewConstructor")
class GlassChargingView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, 272f, 184f) {

    /** [dc] is null when the source is unknown; [kwEstimated] means [kw] must not drive the flow; [socKnown] false hides the %. */
    data class State(
        val active: Boolean, val socPct: Int, val targetPct: Int?, val etaMin: Int?,
        val kw: Float, val kwEstimated: Boolean, val dc: Boolean?, val socKnown: Boolean = true,
    )

    private var state = State(false, 0, null, null, 0f, false, null)
    private val soc = Spring.liquid()
    private val eta = Spring.number()
    private var cell: CellPainter? = null
    private var colorPct = -1
    private var shownEta = -1
    private val text = GlassText(context) { env.theme }
    private val agitation = FloatArray(1)

    // particles: streaks of light, longer and faster at higher kW, brighter on DC
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sparkShader: RadialGradient? = null
    private var inletShader: RadialGradient? = null
    private var sparkColor = 0
    private var speed = 24f
    private var count = 0
    private var sparkAlpha = 0.7f
    private var sparkSize = 1f
    private var stillFrame = false

    private var bound = false
    private val charging get() = state.active && state.socPct < (state.targetPct ?: 100)

    /** Unchanged data is a no-op; [force] re-applies it (theme change). */
    fun bind(s: State, animate: Boolean, force: Boolean = false) {
        if (!force && bound && s == state) return
        bound = true
        state = s
        val instant = !animate || env.reduceMotion
        // an unknown charge keeps the last level rather than draining the cell
        if (s.socKnown) soc.set(s.socPct / 100f, instant)
        val dc = s.dc == true
        sparkAlpha = if (dc) 1f else 0.7f
        sparkSize = if (dc) 1.3f else 1f
        if (s.kwEstimated || s.kw <= 0f) { speed = 24f; count = 8 }
        else { speed = (18f + s.kw).coerceIn(18f, 150f); count = (6f + s.kw / 7f).coerceIn(6f, NP.toFloat()).roundToInt() }
        if (!charging) count = 0

        text.statusIcon = null
        text.statusText = ""
        text.valueColor = 0
        val t = env.theme
        when {
            // shownEta = -1: the next session's first time-to-full snaps instead of counting from this one
            !s.active -> { text.labelText = gs(R.string.glass_not_charging); text.setWord(gs(R.string.glass_unplugged), t.text2); shownEta = -1 }
            !charging -> { text.labelText = gs(R.string.glass_limit_reached); text.setValue(gs(R.string.glass_full), ""); shownEta = -1 }
            s.etaMin == null -> { text.labelText = gs(R.string.glass_full_in); text.setWord(gs(R.string.glass_estimating), t.text2); shownEta = -1 }
            else -> {
                text.labelText = gs(R.string.glass_full_in)
                if (instant || shownEta < 0) eta.snap(s.etaMin.toFloat()) else eta.set(s.etaMin.toFloat(), false)
                shownEta = -1
                updateEta()
            }
        }
        val pct = if (s.socKnown) "${s.socPct}%" else null
        if (charging) {
            text.statusIcon = GlassPaths.svg(BOLT)
            val source = when (s.dc) { true -> gs(R.string.glass_dc_fast); false -> "AC"; null -> null }
            text.statusText = when {
                pct != null && source != null -> "$pct · $source"
                else -> pct ?: source ?: gs(R.string.glass_state_charging)
            }
            text.statusColor = if (s.dc == true) t.accent else t.text2
        } else if (pct != null) {
            text.statusText = pct
            text.statusColor = 0
        }
        requestFrames()
        invalidate()
    }

    private fun updateEta() {
        if (!charging || state.etaMin == null) return
        val m = eta.value.roundToInt().coerceAtLeast(0)
        if (m == shownEta) return
        shownEta = m
        if (m < 60) text.setValue(m.toString(), gs(R.string.glass_unit_min))
        else if (m % 60 == 0) text.setValue((m / 60).toString(), gs(R.string.glass_unit_h))
        else text.setValue((m / 60).toString(), gs(R.string.glass_unit_h), (m % 60).toString(), gs(R.string.glass_unit_min))
    }

    private fun painter(): CellPainter {
        cell?.let { return it }
        val capH = minOf(154f, baseH - 30f)
        return CellPainter(env.theme, 26f, (baseH - capH) / 2f, 62f * capH / 154f, capH, false).also { cell = it; colorPct = -1 }
    }

    override fun animate(nowMs: Long, dt: Float): Boolean {
        val a = soc.step(dt); val b = eta.step(dt)
        updateEta()
        val flowing = charging && env.allows(GlassEnv.Loop.AMBIENT, stale)
        if (!flowing && charging && !stillFrame) { stillFrame = true; return true }
        if (flowing) stillFrame = false
        return a || b || flowing
    }

    override fun onThemeChanged() { cell = null; sparkShader = null; inletShader = null; if (bound) bind(state, false, force = true) }
    override fun drawBack(c: Canvas) = painter().drawBack(c)
    override fun drawFront(c: Canvas) = painter().drawFront(c)

    override fun drawLive(c: Canvas, nowMs: Long) {
        val p = painter()
        val sp = (soc.value * 100f).roundToInt().coerceIn(0, 100)
        if (sp != colorPct) {
            colorPct = sp
            val col = GlassBatteryView.socColor(env.theme, sp.toFloat())
            p.setColor(0, col)
            if (col != sparkColor) {
                sparkColor = col
                sparkShader = RadialGradient(0f, 0f, 1f, intArrayOf(0xFFFFFFFF.toInt(), GlassColor.alpha(GlassColor.lighten(col, 0.55f), 0.9f), GlassColor.clear(GlassColor.lighten(col, 0.55f))),
                    floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
                inletShader = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(GlassColor.lighten(col, 0.6f), 0.75f), GlassColor.clear(GlassColor.lighten(col, 0.6f))), null, Shader.TileMode.CLAMP)
            }
        }
        p.setLevel(0, soc.value)
        agitation[0] = soc.velocity
        val flowing = charging && env.allows(GlassEnv.Loop.AMBIENT, stale)
        sparkTime = if (flowing) nowMs else 2600L
        p.drawLive(c, agitation, if (charging) sparks else null)
        state.targetPct?.let { p.drawLimit(c, it / 100f) }
        text.draw(c, p.cells[0].x + p.cells[0].rx * 2f + 22f, 0f, shellH)
    }

    private var sparkTime = 0L
    private val sparks = CellPainter.Particles { c, cell -> drawSparks(c, cell, sparkTime) }

    private fun drawSparks(c: Canvas, cell: CellPainter.Cell, nowMs: Long) {
        val inlet = inletShader ?: return
        val spark = sparkShader ?: return
        c.unitOval(sparkPaint, inlet, cell.cx, cell.yB - 4f, 30f, 12f)
        val sy = cell.levelY(cell.level)
        val bot = cell.yB + 2f
        val travel = maxOf(14f, bot - sy)
        // double precision: a head unit's uptime runs to weeks, and float seconds would step the motion
        val sec = nowMs / 1000.0
        for (i in 0 until count) {
            val ph0 = sec * speed / travel + hash(i + 3)
            val ph = (ph0 - floor(ph0)).toFloat()
            val yy = bot - ph * travel
            val xx = cell.cx - cell.lrx + 4f + hash(i + 11) * maxOf(0f, cell.lrx * 2f - 8f) + sin(nowMs / 700.0 + i * 1.7).toFloat() * 1.3f
            val a = sparkAlpha * smooth(0f, 0.14f, ph) * (1f - smooth(0.8f, 1f, ph))
            if (a <= 0.01f) continue
            val r = (3.2f + 2.2f * hash(i + 29)) * sparkSize
            c.unitOval(sparkPaint, spark, xx, yy, r * 0.8f, r * (1f + speed / 80f), (a * 255f).roundToInt())
        }
    }

    companion object {
        private const val NP = 18
        const val BOLT = "M7.4 0.6 L1.2 9 H5.6 L4.4 15.4 L10.8 6.6 H6.4 Z"
        fun hash(i: Int): Float { val s = sin(i * 127.1 + 311.7) * 43758.5453; return (s - floor(s)).toFloat() }
        fun smooth(a: Float, b: Float, x: Float): Float { val k = ((x - a) / (b - a)).coerceIn(0f, 1f); return k * k * (3f - 2f * k) }
    }
}
