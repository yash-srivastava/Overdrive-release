package com.overdrive.app.overlay.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Drive dynamics — speed in a ring that swings right with the power drawn and left with regen, the
 * motor's rpm under it, and beside it the accelerator and brake pedals and the g-force of speeding up
 * or slowing down. Samples arrive a few times a second while driving; springs carry the motion between.
 */
@SuppressLint("ViewConstructor")
class GlassDynamicsView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, 520f, 184f) {

    private val speed = Spring(0f, 14f, 1f)
    private val power = Spring(0f, 12f, 1f)
    private val accel = Spring(0f, 18f, 1f)
    private val brake = Spring(0f, 18f, 1f)
    private val g = Spring(0f, 9f, 1f)

    // what the car last reported; NaN = not reported
    private var kwKnown = false
    private var accelKnown = false
    private var brakeKnown = false
    private var shownRpm = Int.MAX_VALUE
    private var newest = 0L
    // speed history for the g-force: (uptime ms, m/s), the last couple of seconds
    private val hT = LongArray(H)
    private val hV = FloatArray(H)
    private var hN = 0
    private var hNext = 0

    private val caption = GlassFonts.paint(context, 600, 11.5f, 0).apply { letterSpacing = 0.08f }
    private val small = GlassFonts.paint(context, 500, 10.5f, 0).apply { letterSpacing = 0.06f }
    private val speedNum = GlassFonts.paint(context, 700, 42f, 0).apply { textAlign = Paint.Align.CENTER }
    private val unit = GlassFonts.paint(context, 500, 13f, 0).apply { textAlign = Paint.Align.CENTER }
    private val kwText = GlassFonts.paint(context, 600, 14f, 0).apply { textAlign = Paint.Align.CENTER }
    private val rpmText = GlassFonts.paint(context, 500, 11.5f, 0).apply { textAlign = Paint.Align.CENTER }
    private val value = GlassFonts.paint(context, 700, 16f, 0).apply { textAlign = Paint.Align.RIGHT }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()

    // strings change only when the shown figure does
    private var speedStr = DASH
    private var kwStr = ""
    private var rpmStr = ""
    private var accelStr = DASH
    private var brakeStr = DASH
    private var gStr = DASH
    private var shownSpeed = Int.MIN_VALUE
    private var shownKw = Int.MIN_VALUE
    private var shownAccel = Int.MIN_VALUE
    private var shownBrake = Int.MIN_VALUE
    private var shownG = Int.MIN_VALUE
    private var shaderNight: Boolean? = null
    private var accelFill: LinearGradient? = null
    private var brakeFill: LinearGradient? = null
    private var headDrive: RadialGradient? = null
    private var headRegen: RadialGradient? = null
    private var dotShader: RadialGradient? = null

    // parked, the figures dim at once rather than when the samples are next missed
    private val streaming get() = env.driving && newest > 0L && SystemClock.uptimeMillis() - newest < STALE_MS

    override val minFrameMs: Long get() = FRAME_MS

    /** One reading: km/h, net kW (+ drawing, − regen), pedals 0-100 and motor rpm; NaN where the car gave none. */
    fun addSample(uptimeMs: Long, kmh: Float, kw: Float, accelPct: Float, brakePct: Float, motorRpm: Float) {
        val instant = env.reduceMotion
        if (!kmh.isNaN()) {
            speed.set(max(0f, kmh), instant || newest == 0L)
            push(uptimeMs, max(0f, kmh) / 3.6f)
            g.set(gForce(), instant)
        }
        kwKnown = !kw.isNaN()
        if (kwKnown) power.set(kw, instant)
        accelKnown = !accelPct.isNaN()
        if (accelKnown) accel.set(accelPct.coerceIn(0f, 100f), instant)
        brakeKnown = !brakePct.isNaN()
        if (brakeKnown) brake.set(brakePct.coerceIn(0f, 100f), instant)
        // the figure in tens: the text changes only when the shown rpm does
        val rpm10 = if (motorRpm.isNaN()) Int.MIN_VALUE else (abs(motorRpm) / 10f).roundToInt() * 10
        if (rpm10 != shownRpm) {
            shownRpm = rpm10
            rpmStr = if (rpm10 == Int.MIN_VALUE) "" else String.format(Locale.US, "%,d rpm", rpm10)
        }
        newest = uptimeMs
        updateTexts(force = true)
        removeCallbacks(streamEnded)
        postDelayed(streamEnded, STALE_MS + 50L)
        requestFrames(); invalidate()
    }

    private val streamEnded = Runnable { invalidate() }

    private fun push(t: Long, v: Float) {
        hT[hNext] = t; hV[hNext] = v
        hNext = (hNext + 1) % H
        if (hN < H) hN++
    }

    // the change in speed over about the last second, in g; speed reads in whole steps, so a longer base
    private fun gForce(): Float {
        if (hN < 2) return 0f
        val last = (hNext - 1 + H) % H
        val tNow = hT[last]
        var j = last
        for (i in 1 until hN) {
            val k = (last - i + H) % H
            j = k
            if (tNow - hT[k] >= G_BASE_MS) break
        }
        val dt = (tNow - hT[j]) / 1000f
        if (dt < 0.25f || dt > 3f) return 0f
        return ((hV[last] - hV[j]) / dt / 9.81f).coerceIn(-1.2f, 1.2f)
    }

    private fun updateTexts(force: Boolean = false) {
        val miles = env.miles
        val sp = GlassUnits.speed(speed.value, miles).roundToInt()
        if (force || sp != shownSpeed) { shownSpeed = sp; speedStr = if (newest == 0L) DASH else sp.toString() }
        val kw = power.value.roundToInt()
        if (force || kw != shownKw) {
            shownKw = kw
            kwStr = when { !kwKnown -> "$DASH kW"; kw > 0 -> "+$kw kW"; kw < 0 -> "−${-kw} kW"; else -> "0 kW" }
        }
        val a = accel.value.roundToInt()
        if (force || a != shownAccel) { shownAccel = a; accelStr = if (accelKnown) "$a%" else DASH }
        val b = brake.value.roundToInt()
        if (force || b != shownBrake) { shownBrake = b; brakeStr = if (brakeKnown) "$b%" else DASH }
        val gg = (g.value * 100f).roundToInt()
        if (force || gg != shownG) {
            shownG = gg
            gStr = if (newest == 0L) DASH else String.format(Locale.US, "%s%.2f g", if (gg > 0) "+" else if (gg < 0) "−" else "", abs(gg) / 100f)
        }
    }

    override fun animate(nowMs: Long, dt: Float): Boolean {
        var busy = false
        if (speed.step(dt)) busy = true
        if (power.step(dt)) busy = true
        if (accel.step(dt)) busy = true
        if (brake.step(dt)) busy = true
        if (g.step(dt)) busy = true
        updateTexts()
        return busy
    }

    override fun onThemeChanged() { shaderNight = null; updateTexts(force = true) }

    override fun envChanged() { updateTexts(force = true); super.envChanged() }

    private fun ensureShaders(t: GlassTheme) {
        if (shaderNight == t.night) return
        shaderNight = t.night
        accelFill = LinearGradient(PX0, 0f, PX1, 0f, t.glowGood, t.glowAccent, Shader.TileMode.CLAMP)
        brakeFill = LinearGradient(PX0, 0f, PX1, 0f, t.glowWarn, t.glowDanger, Shader.TileMode.CLAMP)
        fun head(c: Int) = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(0xFFFFFFFF.toInt(), 0.95f), GlassColor.alpha(c, 0.55f), GlassColor.clear(c)), floatArrayOf(0f, 0.32f, 1f), Shader.TileMode.CLAMP)
        headDrive = head(t.glowAccent); headRegen = head(t.regen)
        dotShader = head(t.glowAccent)
    }

    // ── baked: the ring's track and ticks, the panel's captions and bar tracks ─────────────────

    override fun drawBack(c: Canvas) {
        val t = env.theme
        val ink = t.ink
        // the dial's glass lens
        p.shade(RadialGradient(CX, CY, R + 14f, intArrayOf(GlassColor.alpha(ink, if (t.night) 0.05f else 0.04f), GlassColor.alpha(ink, if (t.night) 0.08f else 0.06f), GlassColor.clear(ink)), floatArrayOf(0f, 0.86f, 1f), Shader.TileMode.CLAMP))
        c.drawCircle(CX, CY, R + 14f, p)
        p.shader = null
        s.shader = null; s.strokeWidth = RW; s.color = GlassColor.alpha(ink, if (t.night) 0.10f else 0.09f)
        r.set(CX - R, CY - R, CX + R, CY + R)
        c.drawArc(r, -90f - SWEEP, SWEEP * 2f, false, s)
        // ticks: every 25 kW drawn, every 20 kW of regen, and zero at the top
        s.strokeWidth = 1.4f; s.color = GlassColor.alpha(ink, if (t.night) 0.28f else 0.3f)
        var k = 25f
        while (k <= DRIVE_FULL + 0.1f) { tick(c, -90f + SWEEP * k / DRIVE_FULL, 2.5f); k += 25f }
        k = 20f
        while (k <= REGEN_FULL + 0.1f) { tick(c, -90f - SWEEP * k / REGEN_FULL, 2.5f); k += 20f }
        s.strokeWidth = 2f; s.color = GlassColor.alpha(ink, if (t.night) 0.6f else 0.55f)
        tick(c, -90f, 4f)
        small.color = t.text3
        small.textAlign = Paint.Align.CENTER
        c.drawText(gs(com.overdrive.app.R.string.glass_dyn_regen), CX - R * 0.77f, LABEL_Y, small)
        c.drawText(gs(com.overdrive.app.R.string.glass_dyn_drive), CX + R * 0.77f, LABEL_Y, small)
        // the panel
        caption.color = t.text2
        c.drawText(gs(com.overdrive.app.R.string.glass_dyn_accelerator), PX0, ROW1 + 10f, caption)
        c.drawText(gs(com.overdrive.app.R.string.glass_dyn_brake), PX0, ROW2 + 10f, caption)
        c.drawText(gs(com.overdrive.app.R.string.glass_dyn_gforce), PX0, ROW3 + 10f, caption)
        p.color = GlassColor.alpha(ink, if (t.night) 0.09f else 0.08f)
        for (y in floatArrayOf(ROW1, ROW2, ROW3)) { r.set(PX0, y + BAR_DY, PX1, y + BAR_DY + BAR_H); c.drawRoundRect(r, BAR_H / 2f, BAR_H / 2f, p) }
        s.strokeWidth = 1.2f; s.color = GlassColor.alpha(ink, if (t.night) 0.4f else 0.36f)
        val mid = (PX0 + PX1) / 2f
        c.drawLine(mid, ROW3 + BAR_DY - 3f, mid, ROW3 + BAR_DY + BAR_H + 3f, s)
        small.textAlign = Paint.Align.LEFT
        c.drawText("slowing", PX0, ROW3 + BAR_DY + BAR_H + 13f, small)
        small.textAlign = Paint.Align.RIGHT
        c.drawText("speeding up", PX1, ROW3 + BAR_DY + BAR_H + 13f, small)
    }

    private fun tick(c: Canvas, deg: Float, len: Float) {
        val a = Math.toRadians(deg.toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val r0 = R + RW / 2f + 2f
        c.drawLine(CX + ca * r0, CY + sa * r0, CX + ca * (r0 + len), CY + sa * (r0 + len), s)
    }

    override fun drawLive(c: Canvas, nowMs: Long) {
        val t = env.theme
        ensureShaders(t)
        val live = streaming
        // a stream that stopped (parked, hidden) leaves its last figures dimmed
        val dim = if (live) 1f else 0.45f
        drawRing(c, t, dim)
        speedNum.color = GlassColor.alpha(t.text, dim)
        c.drawText(speedStr, CX, CY + 16f, speedNum)
        unit.color = t.text2
        c.drawText(GlassUnits.speedUnit(env.miles), CX, CY + 34f, unit)
        val kw = power.value
        kwText.color = when { !kwKnown -> t.text3; kw >= 0.5f -> t.accent; kw <= -0.5f -> t.regenText; else -> t.text2 }
        kwText.alpha = (kwText.alpha * dim).roundToInt()
        c.drawText(kwStr, CX, CY - 30f, kwText)
        rpmText.color = t.text2
        val bottom = if (!live) gs(com.overdrive.app.R.string.glass_dyn_live_note) else rpmStr
        if (bottom.isNotEmpty()) c.drawText(bottom, CX, RPM_Y, rpmText)

        drawBar(c, t, ROW1, accel.value / 100f, accelKnown, accelFill, accelStr, dim)
        drawBar(c, t, ROW2, brake.value / 100f, brakeKnown, brakeFill, brakeStr, dim)
        drawG(c, t, dim)
    }

    // the power ring: from zero at the top, clockwise for power drawn and anticlockwise for regen
    private fun drawRing(c: Canvas, t: GlassTheme, dim: Float) {
        if (!kwKnown) return
        val kw = power.value
        val f = if (kw >= 0f) (kw / DRIVE_FULL).coerceIn(0f, 1f) else (-kw / REGEN_FULL).coerceIn(0f, 1f)
        if (f < 0.004f) return
        val sweep = SWEEP * f * if (kw >= 0f) 1f else -1f
        val col = if (kw >= 0f) t.glowAccent else t.regen
        r.set(CX - R, CY - R, CX + R, CY + R)
        s.shader = null
        s.strokeWidth = RW * 2.4f; s.color = GlassColor.alpha(col, 0.16f * dim)
        c.drawArc(r, -90f, sweep, false, s)
        s.strokeWidth = RW; s.color = GlassColor.alpha(col, dim)
        c.drawArc(r, -90f, sweep, false, s)
        s.strokeWidth = 1.2f; s.color = GlassColor.alpha(GlassColor.lighten(col, 0.6f), 0.8f * dim)
        r.set(CX - R - RW / 2f + 1f, CY - R - RW / 2f + 1f, CX + R + RW / 2f - 1f, CY + R + RW / 2f - 1f)
        c.drawArc(r, -90f, sweep, false, s)
        val a = Math.toRadians((-90f + sweep).toDouble())
        val hx = CX + R * cos(a).toFloat(); val hy = CY + R * sin(a).toFloat()
        (if (kw >= 0f) headDrive else headRegen)?.let { c.unitOval(p, it, hx, hy, 11f, 11f, (255 * dim).roundToInt()) }
        p.shader = null
    }

    private fun drawBar(c: Canvas, t: GlassTheme, top: Float, f: Float, known: Boolean, fill: Shader?, text: String, dim: Float) {
        value.color = GlassColor.alpha(if (known) t.text else t.text3, dim)
        c.drawText(text, PX1, top + 11f, value)
        if (!known || f < 0.005f) return
        val x1 = PX0 + (PX1 - PX0) * f.coerceIn(0f, 1f)
        val y = top + BAR_DY
        r.set(PX0, y, max(PX0 + BAR_H, x1), y + BAR_H)
        p.shade(fill).alpha = (255 * dim).roundToInt()
        c.drawRoundRect(r, BAR_H / 2f, BAR_H / 2f, p)
        p.shader = null
        p.color = GlassColor.alpha(0xFFFFFFFF.toInt(), (if (t.night) 0.35f else 0.5f) * dim)
        r.set(r.left + 2f, y + 1.5f, max(r.left + 2f, r.right - 2f), y + 3f)
        c.drawRoundRect(r, 1f, 1f, p)
    }

    // speeding up fills right of the middle, slowing down left; the dot marks the figure
    private fun drawG(c: Canvas, t: GlassTheme, dim: Float) {
        val gv = g.value
        value.color = GlassColor.alpha(when { newest == 0L -> t.text3; gv >= 0.02f -> t.accent; gv <= -0.02f -> t.warn; else -> t.text }, dim)
        c.drawText(gStr, PX1, ROW3 + 11f, value)
        if (newest == 0L) return
        val mid = (PX0 + PX1) / 2f
        val x = mid + (PX1 - PX0) / 2f * (gv / G_FULL).coerceIn(-1f, 1f)
        val y = ROW3 + BAR_DY
        if (abs(x - mid) > 1f) {
            p.shader = null
            p.color = GlassColor.alpha(if (gv >= 0f) t.glowAccent else t.glowWarn, 0.9f * dim)
            r.set(minOf(mid, x), y, max(mid, x), y + BAR_H)
            c.drawRoundRect(r, BAR_H / 2f, BAR_H / 2f, p)
        }
        dotShader?.let { c.unitOval(p, it, x, y + BAR_H / 2f, 10f, 10f, (255 * dim).roundToInt()) }
        p.shader = null; p.color = GlassColor.alpha(0xFFFFFFFF.toInt(), dim)
        c.drawCircle(x, y + BAR_H / 2f, 3.6f, p)
    }

    override fun drawFront(c: Canvas) {}

    companion object {
        private const val DASH = "—"
        private const val H = 16
        private const val G_BASE_MS = 900L
        private const val STALE_MS = 3000L
        private const val FRAME_MS = 33L
        // the dial
        private const val CX = 112f
        private const val CY = 94f
        private const val R = 68f
        private const val RW = 9f
        private const val SWEEP = 130f
        private const val DRIVE_FULL = 150f
        private const val REGEN_FULL = 60f
        private const val LABEL_Y = 166f
        private const val RPM_Y = 152f
        // the panel
        private const val PX0 = 222f
        private const val PX1 = 496f
        private const val ROW1 = 28f
        private const val ROW2 = 74f
        private const val ROW3 = 120f
        private const val BAR_DY = 18f
        private const val BAR_H = 9f
        private const val G_FULL = 0.6f
    }
}
