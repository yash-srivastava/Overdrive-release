package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Vehicle power & tyres — four wheels (which are also the tyre gauges), a motor on each driven axle
 * and the pack between them; energy flows out to the wheels and back into the pack on regen, and
 * optional blind-spot zones light beside the rear quarters. The chassis is baked; only the flow, the
 * power-dependent glow and a wheel that is changing shape or pulsing draw per frame.
 */
@SuppressLint("ViewConstructor")
class GlassPowerView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, 400f, 184f) {

    enum class Phase { DRIVING, CHARGING, PARKED }
    enum class Drive { AWD, FWD, RWD }

    /**
     * [kw] is pack power (negative on regen), or charging power while charging; null when the car
     * doesn't report it, and the widget then only shows that it is driving. Tyres in bar, null = no
     * reading. Blind-spot levels: 0 clear, 1 object, 2 closing; [bsAny] is a vehicle the car places
     * in a blind spot without saying which side.
     */
    data class State(
        val phase: Phase = Phase.PARKED, val kw: Float? = null, val drive: Drive = Drive.AWD,
        val phev: Boolean = false, val engineKw: Float = 0f, val soc: Float = 0f,
        val showBs: Boolean = false, val bsLeft: Int = 0, val bsRight: Int = 0, val bsAny: Int = 0,
        val fl: Float? = null, val fr: Float? = null, val rl: Float? = null, val rr: Float? = null,
        val loBar: Float = 2.34f, val hiBar: Float = 3.10f,
        /** Core's verdict per corner [FL, FR, RL, RR], null where it gave none (the band decides then). */
        val tyreStates: List<String?>? = null,
    )

    private enum class Mode { DRIVE, REGEN, HYBRID, CHARGING, PARKED, COAST, UNKNOWN }

    /** [left] wheels are drawn on top: the car's nose points right. */
    private class Wheel(val x: Float, val y: Float, val front: Boolean, val left: Boolean) {
        var p: Float? = null
        var info = GlassTyres.classify(2.5f, 2.34f, 3.1f)
        val b = Spring.liquid(0f)
        var onset = -1L
        var live = false
        val top = Path()
        val side = Path()
        val tread = Path()
        var builtB = Float.NaN
        var builtNight: Boolean? = null
        var rubber: Shader? = null
        var edge: Shader? = null
        // the sag and theme object mode last shaded this wheel for
        var restB = Float.NaN
        var restNight: Boolean? = null
        var tagged = false
        var mark = ""
        var markColor = 0
    }

    /** A flow route through the chassis as a polyline, with its segment lengths. */
    private class Route(vararg val pts: Float) {
        val seg = FloatArray(pts.size / 2 - 1) { i -> hypot(pts[i * 2 + 2] - pts[i * 2], pts[i * 2 + 3] - pts[i * 2 + 1]) }
        val len = seg.sum()

        fun at(u: Float, out: FloatArray) {
            var dl = u * len
            for (i in seg.indices) {
                if (dl <= seg[i]) {
                    val k = if (seg[i] > 0f) dl / seg[i] else 0f
                    out[0] = pts[i * 2] + (pts[i * 2 + 2] - pts[i * 2]) * k
                    out[1] = pts[i * 2 + 1] + (pts[i * 2 + 3] - pts[i * 2 + 1]) * k
                    return
                }
                dl -= seg[i]
            }
            out[0] = pts[pts.size - 2]; out[1] = pts[pts.size - 1]
        }
    }

    private class Stream(val key: Char, vararg val routes: Route)

    private class Tag { var on = false; var num = ""; var unit = ""; var color = 0; var x = 0f; var y = 0f }

    private var state = State()
    private var mode = Mode.PARKED
    private val wheels = arrayOf(Wheel(XF, YT, true, true), Wheel(XF, YB, true, false), Wheel(XR, YT, false, true), Wheel(XR, YB, false, false))
    // pack end → motor → each wheel (reversed on regen); plug → pack; engine → front axle
    private val streams = arrayOf(
        Stream('r', Route(BX0, CY, XR, CY, XR, YT + TWD / 2f), Route(BX0, CY, XR, CY, XR, YB - TWD / 2f)),
        Stream('f', Route(BX1, CY, XF, CY, XF, YT + TWD / 2f), Route(BX1, CY, XF, CY, XF, YB - TWD / 2f)),
        Stream('c', Route(PACK_CX, CY + 36f, PACK_CX, CY + BH / 2f)),
        Stream('e', Route(EX, CY - 21f, EX, CY - 8f, XF, CY - 8f, XF, YT + TWD / 2f), Route(EX, CY - 21f, EX, CY - 8f, XF, CY - 8f, XF, YB - TWD / 2f)),
    )
    private val active = BooleanArray(4)
    private var reverse = false
    private var speed = 30f
    private var count = 4
    private var flowAlpha = 0.8f
    private var flowSize = 0f
    private var glowK = 0.25f
    private var bsOn = false
    private var bsPulse = 1f
    private var liveMask = 0
    private var lastDriving = false
    private var staticSig = ""
    private var bound = false
    private val tags = arrayOf(Tag(), Tag())
    // set only inside drawObject: the parts that draw, and that the text column is left out
    private var drawParts = PARTS_ALL
    private var objectMode = false
    // object mode draws the pack every frame and socColor builds its ramp per call: the cells' colour per soc and theme
    private var cellSoc = -1
    private var cellNight: Boolean? = null
    private var cellMc = 0
    private var restGeom: GlassTyres.Geom? = null

    private val kw = Spring.number()
    private var shownKw = Int.MIN_VALUE
    private val text = GlassText(context) { env.theme }
    private val tagNum = GlassFonts.paint(context, 700, 24f, 0)
    private val tagUnit = GlassFonts.paint(context, 600, 12f, 0)
    private val markNum = GlassFonts.paint(context, 600, 15f, 0)
    private val fm = Paint.FontMetrics()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dash = DashPathEffect(floatArrayOf(3f, 3f), 0f)
    private val m = Matrix()
    private val r = RectF()
    private val pt = FloatArray(2)
    private val geom = GlassTyres.Geom()
    private val arrows = Path()
    private val body = GlassPaths.svg(bodyOutline())
    private val chevron = GlassPaths.svg("M${XF + TL / 2f + 10f} ${CY - 6f} L${XF + TL / 2f + 16f} $CY L${XF + TL / 2f + 10f} ${CY + 6f}")
    private val plug = GlassPaths.svg("M${PACK_CX - 5f} ${CY + 40f} h10 v6 a5 5 0 0 1 -10 0 Z M${PACK_CX - 3f} ${CY + 36f} v4 M${PACK_CX + 3f} ${CY + 36f} v4")
    private val carWindows = Array(2) { i -> GlassPaths.svg(windows(GX, zoneY(i == 0) + 12f)) }

    // theme shaders, unit-sized and placed with local matrices
    private var shaderNight: Boolean? = null
    private lateinit var shadowSh: RadialGradient
    private lateinit var specSh: RadialGradient
    private lateinit var metalH: LinearGradient
    private lateinit var metalV: LinearGradient
    private lateinit var haloWarn: RadialGradient
    private lateinit var haloDanger: RadialGradient
    private lateinit var zoneWarn: LinearGradient
    private lateinit var zoneDanger: LinearGradient
    private lateinit var fuelGlow: RadialGradient
    // flow-colour shaders
    private var flowCol = 0
    private var motorGlow: RadialGradient? = null
    private var dotSh: RadialGradient? = null

    private val flowing get() = anyActive() && env.allows(GlassEnv.Loop.DRIVE, stale)

    override val hasMid get() = true

    /** Unchanged data is a no-op; [force] re-applies it (theme change). */
    fun bind(st: State, animate: Boolean, force: Boolean = false) {
        if (!force && bound && st == state) return
        // the first measured charging rate after a dash appears at once, not counted up from zero
        val afterDash = bound && mode == Mode.CHARGING && state.kw == null && st.kw != null
        bound = true
        state = st
        mode = modeOf(st)
        val t = env.theme
        val instant = !animate || env.reduceMotion
        val front = st.drive != Drive.RWD
        val rear = st.drive != Drive.FWD
        val moving = mode == Mode.DRIVE || mode == Mode.REGEN || mode == Mode.HYBRID || mode == Mode.UNKNOWN
        active[0] = moving && rear; active[1] = moving && front
        active[2] = mode == Mode.CHARGING; active[3] = mode == Mode.HYBRID
        reverse = mode == Mode.REGEN
        val unknown = mode == Mode.UNKNOWN
        val mag = if (unknown) 30f else abs(st.kw ?: 0f) + if (mode == Mode.HYBRID) st.engineKw else 0f
        speed = if (unknown) 30f else (24f + mag * 0.9f).coerceIn(24f, 150f)
        count = if (unknown) 3 else (2f + mag / 18f).coerceIn(2f, NP.toFloat()).roundToInt()
        flowAlpha = if (unknown) 0.55f else 0.95f
        flowSize = (mag / 120f).coerceIn(0f, 1f)
        glowK = if (unknown) 0.35f else (mag / 110f).coerceIn(0.25f, 1f)
        // regen must not share the drive hue
        setFlowColor(if (reverse) t.regen else t.glowAccent)
        bsOn = st.showBs && st.phase == Phase.DRIVING
        updateTyres(st, instant)
        updateText()
        val target = (st.kw ?: 0f) + if (mode == Mode.HYBRID) st.engineKw else 0f
        kw.set(target, instant || afterDash)
        shownKw = Int.MIN_VALUE
        updateValue()
        buildArrows()
        syncLive(SystemClock.uptimeMillis())
        val sig = signature()
        if (sig != staticSig) { staticSig = sig; layersChanged() }
        requestFrames(); invalidate()
    }

    private fun modeOf(st: State): Mode {
        val k = st.kw
        return when {
            st.phase == Phase.CHARGING -> Mode.CHARGING
            st.phase == Phase.PARKED -> Mode.PARKED
            k == null -> Mode.UNKNOWN
            abs(k) < 0.5f && !(st.phev && st.engineKw > 0f) -> Mode.COAST
            k < 0f -> Mode.REGEN
            st.phev && st.engineKw > 0f -> Mode.HYBRID
            else -> Mode.DRIVE
        }
    }

    private fun anyActive() = active[0] || active[1] || active[2] || active[3]

    private fun setFlowColor(c: Int) {
        if (c == flowCol && motorGlow != null) return
        flowCol = c
        motorGlow = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(c, 0.9f), GlassColor.alpha(c, 0.45f), GlassColor.clear(c)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        dotSh = RadialGradient(0f, 0f, 1f, intArrayOf(0xFFFFFFFF.toInt(), GlassColor.alpha(GlassColor.lighten(c, 0.5f), 0.85f), GlassColor.clear(c)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
    }

    // ── tyres ────────────────────────────────────────────────────────────────

    private fun updateTyres(st: State, instant: Boolean) {
        val now = SystemClock.uptimeMillis()
        val ps = arrayOf(st.fl, st.fr, st.rl, st.rr)
        for ((i, w) in wheels.withIndex()) {
            val prev = w.info
            w.p = ps[i]
            w.info = GlassTyres.classify(ps[i], st.loBar, st.hiBar, st.tyreStates?.getOrNull(i))
            w.b.set(w.info.b, instant)
            if (w.info.crit && (!prev.crit || w.onset < 0)) w.onset = now
            if (!w.info.crit) w.onset = -1
        }
        // one tag per side (the worse tyre on it), inside the chassis beside that wheel
        for (w in wheels) w.tagged = false
        for (side in 0..1) {
            val tag = tags[side]
            val a = wheels[if (side == 0) 0 else 1]; val b = wheels[if (side == 0) 2 else 3]
            val cand = listOf(a, b).filter { it.info.rank >= 2 }.maxWithOrNull(compareBy<Wheel>({ it.info.rank }, { it.info.dev }))
            val p = cand?.p
            tag.on = cand != null && p != null
            if (cand == null || p == null) continue
            cand.tagged = true
            tag.num = GlassTyres.number(p, env.units)
            tag.unit = GlassTyres.unit(env.units)
            tag.color = if (cand.info.crit) env.theme.danger else env.theme.warn
            tag.x = if (cand.front) cand.x - TL / 2f - 34f else cand.x + TL / 2f + 34f
            tag.y = cand.y
        }
        // every other tyre with a reading shows it quietly beside its wheel, tinted only when out of band
        val t = env.theme
        for (w in wheels) {
            val p = w.p
            w.mark = if (p == null || w.info.kind == GlassTyres.Kind.MISSING || w.tagged) "" else GlassTyres.number(p, env.units)
            w.markColor = if (w.info.rank < 2) t.text2 else if (w.info.crit) t.danger else t.warn
        }
        // a wide tag (kPa) would crowd the other number on its row, so it slides toward its own wheel
        for (side in 0..1) {
            val tag = tags[side]
            if (!tag.on) continue
            val other = wheels[if (side == 0) 0 else 1].takeIf { !it.tagged } ?: wheels[if (side == 0) 2 else 3]
            if (other.mark.isEmpty()) continue
            val half = tagWidth(tag) / 2f
            val mw = markNum.measureText(other.mark)
            if (other.front) {
                val gap = other.x - TL / 2f - MARK_GAP - mw - (tag.x + half)
                if (gap < TAG_GAP) tag.x -= TAG_GAP - gap
            } else {
                val gap = tag.x - half - (other.x + TL / 2f + MARK_GAP + mw)
                if (gap < TAG_GAP) tag.x += TAG_GAP - gap
            }
        }
    }

    private fun pulsing(w: Wheel, now: Long) = w.info.crit && w.onset >= 0 && env.allows(GlassEnv.Loop.URGENT, stale) &&
        !(env.driving && now - w.onset > GlassTyres.PULSE_MS * 3)

    private fun pulseAlpha(w: Wheel, now: Long): Float {
        if (!pulsing(w, now)) return 1f
        val c = cos(PI * (now - w.onset) / GlassTyres.PULSE_MS).toFloat()
        return 0.35f + 0.65f * c * c
    }

    /** A wheel draws live while it changes shape, pulses, or sits under a lit blind-spot zone. */
    private fun syncLive(now: Long): Boolean {
        var mask = 0
        for ((i, w) in wheels.withIndex()) {
            val covered = bsOn && !w.front && ((if (w.left) state.bsLeft else state.bsRight) > 0 || bsUnknownSide())
            w.live = w.b.moving || pulsing(w, now) || covered
            if (w.live) mask = mask or (1 shl i)
        }
        if (mask == liveMask) return false
        liveMask = mask
        layersChanged()
        return true
    }

    // ── text ─────────────────────────────────────────────────────────────────

    private fun updateText() {
        val t = env.theme
        text.labelText = if (mode == Mode.CHARGING) gs(R.string.glass_charging_power) else gs(R.string.glass_power)
        var word: String; var color: Int
        when (mode) {
            Mode.DRIVE -> { word = gs(R.string.glass_state_driving); color = t.accent }
            Mode.REGEN -> { word = gs(R.string.glass_state_regen); color = t.regenText }
            Mode.HYBRID -> { word = gs(R.string.glass_state_hybrid); color = t.fuel }
            Mode.CHARGING -> { word = gs(R.string.glass_state_charging); color = t.accent }
            Mode.PARKED -> { word = gs(R.string.glass_state_parked); color = t.text2 }
            Mode.COAST -> { word = gs(R.string.glass_state_coasting); color = t.text2 }
            Mode.UNKNOWN -> { word = ""; color = 0 }
        }
        // the glowing wheel and its tag say which tyre; the words stay short enough for the text column
        var worst: Wheel? = null
        for (w in wheels) {
            val i = w.info
            if (i.rank > 0 && (worst == null || i.rank > worst.info.rank || (i.rank == worst.info.rank && i.dev > worst.info.dev))) worst = w
        }
        worst?.info?.let { inf ->
            val many = wheels.count { it.info.rank >= 2 }
            val low = inf.kind == GlassTyres.Kind.LOW
            word = when {
                inf.kind == GlassTyres.Kind.MISSING -> gs(R.string.glass_tyre_sensor_offline)
                many > 1 -> gs(if (low) R.string.glass_tyres_low_n else R.string.glass_tyres_high_n, many)
                low -> gs(if (inf.crit) R.string.glass_tyre_very_low else R.string.glass_tyre_low)
                else -> gs(if (inf.crit) R.string.glass_tyre_very_high else R.string.glass_tyre_high)
            }
            color = if (inf.kind == GlassTyres.Kind.MISSING) t.text2 else if (inf.crit) t.danger else t.warn
        }
        // a vehicle beside the car outranks everything while it's there
        if (bsOn && (state.bsLeft > 0 || state.bsRight > 0 || state.bsAny > 0)) {
            word = when {
                state.bsLeft > 0 && state.bsRight > 0 -> gs(R.string.glass_bs_both)
                state.bsLeft > 0 -> gs(R.string.glass_bs_left)
                state.bsRight > 0 -> gs(R.string.glass_bs_right)
                else -> gs(R.string.glass_bs_unknown)
            }
            color = t.warn
        }
        text.statusText = word
        text.statusColor = color
    }

    // the car reports a vehicle in a blind spot but not the side: both quarters light, softer
    private fun bsUnknownSide() = state.bsAny > 0 && state.bsLeft <= 0 && state.bsRight <= 0

    // formats only when the shown value changes, not every animation frame
    private fun updateValue() {
        if (mode == Mode.UNKNOWN || mode == Mode.PARKED) {
            val w = if (mode == Mode.PARKED) gs(R.string.glass_state_parked) else gs(R.string.glass_state_driving)
            if (!text.isWord || text.wordText != w) text.setWord(w)
            return
        }
        // the car hasn't measured the charging rate (core only has a placeholder): a dash, never a made-up figure
        if (mode == Mode.CHARGING && state.kw == null) {
            if (shownKw != DASH || text.isWord) { shownKw = DASH; text.setValue("–", "kW") }
            return
        }
        val v = abs(kw.value)
        val tenths = if (v < 10f) (v * 10f).roundToInt() else v.roundToInt() * 10
        if (tenths == shownKw && !text.isWord) return
        shownKw = tenths
        text.setValue(if (tenths < 100) "${tenths / 10}.${tenths % 10}" else (tenths / 10).toString(), "kW")
    }

    private fun signature() = buildString {
        append(mode.ordinal).append(state.drive.ordinal).append(state.phev).append(state.soc.roundToInt()).append(env.units)
        for (w in wheels) append('|').append(w.info.rank).append(w.info.kind.ordinal).append(w.info.b).append(w.mark).append(w.markColor)
        for (tg in tags) append('|').append(tg.on).append(tg.num).append(tg.color).append(tg.x)
    }

    // ── motion ───────────────────────────────────────────────────────────────

    override fun animate(nowMs: Long, dt: Float): Boolean {
        var busy = kw.step(dt)
        updateValue()
        for (w in wheels) if (w.b.step(dt)) busy = true
        if (syncLive(nowMs)) busy = true
        for (w in wheels) if (pulsing(w, nowMs)) busy = true
        val closing = bsOn && (state.bsLeft == 2 || state.bsRight == 2) && env.allows(GlassEnv.Loop.URGENT, stale)
        if (closing) {
            val c = cos(PI * nowMs / 600.0).toFloat()
            bsPulse = c * c
            busy = true
        } else bsPulse = 1f
        if (flowing) busy = true
        return busy
    }

    override fun envChanged() {
        if (env.driving != lastDriving) {
            lastDriving = env.driving
            // a critical tyre pulses three more times when driving starts, then holds steady
            val now = SystemClock.uptimeMillis()
            for (w in wheels) if (w.info.crit) w.onset = now
        }
        syncLive(SystemClock.uptimeMillis())
        super.envChanged()
    }

    override fun onThemeChanged() {
        shaderNight = null
        flowCol = 0
        if (bound) bind(state, false, force = true)
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    private fun ensureShaders(t: GlassTheme) {
        if (shaderNight == t.night) return
        shaderNight = t.night
        val night = t.night
        val white = 0xFFFFFFFF.toInt()
        shadowSh = RadialGradient(0f, 0f, 1f, GlassColor.alpha(t.shadow, t.shadowA), GlassColor.clear(t.shadow), Shader.TileMode.CLAMP)
        specSh = RadialGradient(0f, 0f, 1f, GlassColor.alpha(white, 0.28f), GlassColor.clear(white), Shader.TileMode.CLAMP)
        val mc = if (night) intArrayOf(0xFF1B2221.toInt(), 0xFF56625F.toInt(), 0xFF98A6A2.toInt(), 0xFF4B5654.toInt(), 0xFF1A201F.toInt())
        else intArrayOf(0xFF8C9793.toInt(), 0xFFD6DDD9.toInt(), white, 0xFFBFC8C4.toInt(), 0xFF86908C.toInt())
        val ms = floatArrayOf(0f, 0.18f, 0.34f, 0.5f, 1f)
        metalH = LinearGradient(0f, 0f, 1f, 0f, mc, ms, Shader.TileMode.CLAMP)
        metalV = LinearGradient(0f, 0f, 0f, 1f, mc, ms, Shader.TileMode.CLAMP)
        fun halo(c: Int) = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.clear(c), GlassColor.alpha(c, 0.55f), GlassColor.alpha(c, 0.2f), GlassColor.clear(c)),
            floatArrayOf(0f, 0.4f, 0.72f, 1f), Shader.TileMode.CLAMP)
        fun zone(c: Int) = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(GlassColor.clear(c), GlassColor.alpha(c, 0.55f), GlassColor.alpha(c, 0.1f)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        haloWarn = halo(t.glowWarn); haloDanger = halo(t.glowDanger)
        zoneWarn = zone(t.glowWarn); zoneDanger = zone(t.glowDanger)
        fuelGlow = RadialGradient(0f, 0f, 1f, GlassColor.alpha(t.fuel, 0.85f), GlassColor.clear(t.fuel), Shader.TileMode.CLAMP)
    }

    /** Chassis hints (etched axles, a "front" chevron, a faint body), the conduit tracks, settled wheels. */
    override fun drawBack(c: Canvas) {
        val t = env.theme
        ensureShaders(t)
        val ink = t.ink
        s.pathEffect = null; s.shader = null
        s.strokeCap = Paint.Cap.ROUND; s.strokeJoin = Paint.Join.MITER
        s.color = t.etch; s.strokeWidth = 1.3f
        if (shows(PART_FLOW)) for (x in AXLES) c.drawLine(x, YT + TWD / 2f + 3f, x, YB - TWD / 2f - 3f, s)
        s.strokeWidth = 1.6f
        if (shows(PART_BODY)) c.drawPath(chevron, s)
        p.shader = null
        p.color = GlassColor.alpha(ink, if (t.night) 0.025f else 0.03f)
        if (shows(PART_BODY)) c.drawPath(body, p)
        s.strokeCap = Paint.Cap.BUTT
        s.color = GlassColor.alpha(ink, if (t.night) 0.16f else 0.18f); s.strokeWidth = 1.1f
        if (shows(PART_BODY)) c.drawPath(body, s)
        if (shows(PART_FLOW)) for (k in 0..1) {
            r.set(if (k == 0) XR + 13f else BX1, CY - 4f, if (k == 0) BX0 else XF - 13f, CY + 4f)
            p.color = GlassColor.alpha(ink, if (t.night) 0.08f else 0.07f)
            c.drawRoundRect(r, 4f, 4f, p)
            s.color = GlassColor.alpha(ink, 0.18f); s.strokeWidth = 0.8f
            c.drawRoundRect(r, 4f, 4f, s)
        }
        if (shows(PART_TYRES)) for (w in wheels) if (!w.live) drawWheel(c, w, 0L)
    }

    /** Blind-spot zones, live wheels, and the shafts, conduits and motor halos that brighten with power. */
    override fun drawLive(c: Canvas, nowMs: Long) {
        val t = env.theme
        ensureShaders(t)
        if (bsOn && shows(PART_BODY)) {
            if (bsUnknownSide()) { drawZone(c, t, true, 1, faint = true); drawZone(c, t, false, 1, faint = true) }
            else { drawZone(c, t, true, state.bsLeft); drawZone(c, t, false, state.bsRight) }
        }
        if (shows(PART_TYRES)) for (w in wheels) if (w.live) drawWheel(c, w, nowMs)
        s.pathEffect = null; s.shader = null; s.strokeCap = Paint.Cap.ROUND; s.strokeWidth = 3f
        for (k in 0..1) {
            val x = AXLES[k]
            val on = active[k]
            s.color = if (on) GlassColor.alpha(flowCol, 0.35f + 0.3f * glowK) else GlassColor.alpha(t.ink, 0.22f)
            if (shows(PART_FLOW)) c.drawLine(x, YT + TWD / 2f, x, YB - TWD / 2f, s)
            if (!on) continue
            p.shader = null; p.color = GlassColor.alpha(flowCol, 0.18f + 0.3f * glowK)
            r.set(if (k == 0) XR + 13f else BX1, CY - 2.2f, if (k == 0) BX0 else XF - 13f, CY + 2.2f)
            if (shows(PART_FLOW)) c.drawRoundRect(r, 2.2f, 2.2f, p)
            if (shows(PART_MOTORS)) motorGlow?.let { oval(c, it, x, CY, 30f, 34f, 0.55f * glowK) }
        }
    }

    /** The pack, plug, motor drums and engine block: above the glow, below the flow. */
    override fun drawMid(c: Canvas) {
        val t = env.theme
        ensureShaders(t)
        val ink = t.ink
        val night = t.night
        val top = CY - BH / 2f
        val hidPack = hideUnless(c, PART_PACK)
        // battery pack: a glass slab with eight modules lit by state of charge
        // the pack's shadow: its rounded rect drawn in the unit space of the elliptical fade
        val hw = (BX1 - BX0) / 2f; val hh = BH / 2f
        c.save(); c.translate(PACK_CX + 4f, top + 6f + hh); c.scale(hw, hh)
        r.set(-1f, -1f, 1f, 1f)
        c.drawRoundRect(r, 7f / hw, 7f / hh, p.shade(shadowSh))
        c.restore()
        r.set(BX0, top, BX1, top + BH)
        if (objectMode) metalIn(c, metalV, true, 7f) else {
            m.setScale(1f, BH); m.postTranslate(0f, top); metalV.setLocalMatrix(m)
            c.drawRoundRect(r, 7f, 7f, p.shade(metalV))
        }
        p.shader = null
        p.color = if (night) 0xFF050908.toInt() else 0xFFDDE4E0.toInt()
        r.set(BX0 + 2.5f, top + 2.5f, BX1 - 2.5f, top + BH - 2.5f)
        c.drawRoundRect(r, 5f, 5f, p)
        val soc = state.soc.roundToInt().coerceIn(0, 100)
        val lit = soc / 100f * 8f
        val mc = if (objectMode) cellColor(t, soc) else GlassColor.alpha(GlassBatteryView.socColor(t, soc.toFloat()), if (night) 0.95f else 0.9f)
        val mw = (BX1 - BX0 - 5f - 14f) / 8f
        for (i in 0 until 8) {
            val mx = BX0 + 2.5f + i * (mw + 2f)
            p.color = GlassColor.alpha(ink, if (night) 0.06f else 0.08f)
            r.set(mx + 1f, top + 4.5f, mx + mw, top + BH - 4.5f)
            c.drawRoundRect(r, 1.6f, 1.6f, p)
            val f = (lit - i).coerceIn(0f, 1f)
            if (f <= 0f) continue
            p.color = mc
            r.right = mx + 1f + (mw - 1f) * f
            c.drawRoundRect(r, 1.6f, 1.6f, p)
        }
        p.color = GlassColor.alpha(0xFFFFFFFF.toInt(), if (night) 0.1f else 0.35f)
        r.set(BX0 + 3f, top + 3f, BX1 - 3f, top + 3f + (BH - 6f) * 0.45f)
        c.drawRoundRect(r, 4f, 4f, p)
        if (hidPack) c.restore()
        // the plug, faint unless charging
        val hidPort = hideUnless(c, PART_PORT)
        // object mode draws these layers every frame, so it bounds them to the plug and to each drum
        if (objectMode) c.saveLayerAlpha(PACK_CX - 7f, CY + 35f, PACK_CX + 7f, CY + 53f, if (mode == Mode.CHARGING) 255 else 64)
        else c.saveLayerAlpha(null, if (mode == Mode.CHARGING) 255 else 64)
        p.color = GlassColor.alpha(ink, 0.55f)
        c.drawPath(plug, p)
        s.pathEffect = null; s.shader = null; s.strokeCap = Paint.Cap.BUTT; s.strokeJoin = Paint.Join.MITER
        s.color = GlassColor.alpha(ink, 0.55f); s.strokeWidth = 1.4f
        c.drawPath(plug, s)
        c.restore()
        if (hidPort) c.restore()
        // motors: metal drums on the axle centres with a coil window; a motor that doesn't drive sits dimmed
        if (shows(PART_MOTORS)) for (k in 0..1) {
            val x = AXLES[k]
            val driven = if (k == 0) state.drive != Drive.FWD else state.drive != Drive.RWD
            if (!driven) { if (objectMode) c.saveLayerAlpha(x - 13f, CY - 18f, x + 19f, CY + 26f, 89) else c.saveLayerAlpha(null, 89) }
            oval(c, shadowSh, x + 3f, CY + 5f, 15f, 20f, 1f)
            r.set(x - 12f, CY - 17f, x + 12f, CY + 17f)
            if (objectMode) metalIn(c, metalH, false, 7f) else {
                m.setScale(24f, 1f); m.postTranslate(x - 12f, 0f); metalH.setLocalMatrix(m)
                c.drawRoundRect(r, 7f, 7f, p.shade(metalH))
            }
            p.shader = null
            p.color = if (night) 0xFF060A09.toInt() else 0xFF2A3230.toInt()
            r.set(x - 6.5f, CY - 11f, x + 6.5f, CY + 11f)
            c.drawRoundRect(r, 4f, 4f, p)
            s.color = 0x59000000; s.strokeWidth = 1f
            for (i in -2..2) {
                val fy = CY + i * 5.5f
                c.drawLine(x - 12f, fy, x - 9f, fy, s)
                c.drawLine(x + 9f, fy, x + 12f, fy, s)
            }
            s.color = GlassColor.alpha(0xFFFFFFFF.toInt(), if (night) 0.3f else 0.8f); s.strokeWidth = 0.8f
            r.set(x - 11.5f, CY - 16.5f, x + 11.5f, CY + 16.5f)
            c.drawRoundRect(r, 6.5f, 6.5f, s)
            if (!driven) c.restore()
        }
        // plug-in hybrid engine block, just behind the front motor
        if (state.phev && shows(PART_MOTORS)) {
            if (mode == Mode.HYBRID) oval(c, fuelGlow, EX, CY - 28f, 18f, 12f, 0.8f)
            r.set(EX - 11f, CY - 36f, EX + 11f, CY - 21f)
            if (objectMode) metalIn(c, metalV, true, 3f) else {
                m.setScale(1f, 15f); m.postTranslate(0f, CY - 36f); metalV.setLocalMatrix(m)
                c.drawRoundRect(r, 3f, 3f, p.shade(metalV))
            }
            p.shader = null
            s.color = 0x59000000; s.strokeWidth = 1.3f
            for (fx in ENGINE_FINS) c.drawLine(EX + fx, CY - 33f, EX + fx, CY - 24f, s)
        }
    }

    /** The coil windows, the flow (or still arrows when motion is off), and the text. */
    override fun drawLiveTop(c: Canvas, nowMs: Long) {
        val glow = motorGlow
        if (glow != null && shows(PART_MOTORS)) for (k in 0..1) {
            if (!active[k]) continue
            val x = AXLES[k]
            c.save(); c.translate(x, CY); c.scale(6.5f, 11f)
            p.shade(glow); p.alpha = ((0.4f + 0.6f * glowK) * 255f).roundToInt()
            r.set(-1f, -1f, 1f, 1f)
            c.drawRoundRect(r, 4f / 6.5f, 4f / 11f, p)
            c.restore()
            p.alpha = 255
        }
        if (flowing) drawDots(c, nowMs)
        else if (anyActive()) {
            s.pathEffect = null; s.shader = null; s.strokeCap = Paint.Cap.ROUND; s.strokeJoin = Paint.Join.ROUND
            s.color = flowCol; s.strokeWidth = 2f
            // charging runs only the plug's route, driving only the others
            if (shows(if (active[2]) PART_PORT else PART_FLOW)) c.drawPath(arrows, s)
        }
        if (!objectMode) text.draw(c, 22f, 0f, shellH)
    }

    /** Each tyre's pressure beside its wheel, and the tags for out-of-band tyres, sit over everything. */
    override fun drawFront(c: Canvas) {
        if (!shows(PART_PRESSURES)) return
        markNum.getFontMetrics(fm)
        val dy = -(fm.ascent + fm.descent) / 2f
        for (w in wheels) {
            if (w.mark.isEmpty()) continue
            markNum.color = w.markColor
            // inside the chassis, nearer the wheel than a tag, so a tag on the same side never covers it
            val x = if (w.front) w.x - TL / 2f - MARK_GAP - markNum.measureText(w.mark) else w.x + TL / 2f + MARK_GAP
            c.drawText(w.mark, x, w.y + dy, markNum)
        }
        for (tg in tags) if (tg.on) drawTag(c, tg)
    }

    /**
     * The car alone, for a user widget: the content passes in design units with no text column, drawing only
     * [parts] (PART_* bits). [nowMs] is uptime, as in onDraw.
     */
    internal fun drawObject(c: Canvas, nowMs: Long, parts: Int) {
        drawParts = parts; objectMode = true
        try {
            drawBack(c)
            drawLive(c, nowMs)
            if (hasMid) { drawMid(c); drawLiveTop(c, nowMs) }
            drawFront(c)
        } finally { drawParts = PARTS_ALL; objectMode = false }
    }

    /**
     * Steps everything [tick] does, but stays busy only while something [parts] draw still moves: a wheel's sag or
     * pulse, the closing blind-spot pulse, and the dots on a route the parts show. The power value is text only.
     */
    internal fun tickObject(nowMs: Long, dt: Float, parts: Int): Boolean {
        tick(nowMs, dt)
        var busy = false
        if ((parts and PART_TYRES) != 0) for (w in wheels) if (w.b.moving || pulsing(w, nowMs)) busy = true
        if ((parts and PART_BODY) != 0 && bsOn && (state.bsLeft == 2 || state.bsRight == 2) && env.allows(GlassEnv.Loop.URGENT, stale)) busy = true
        if (flowing && (((parts and PART_FLOW) != 0 && (active[0] || active[1] || active[3])) || ((parts and PART_PORT) != 0 && active[2]))) busy = true
        return busy
    }

    /** The union of [parts]' footprints in design units, strokes, glows and shadows included, a unit beyond. */
    internal fun objectBounds(parts: Int, out: RectF) {
        out.setEmpty()
        if ((parts and PART_BODY) != 0) {
            out.union(BODY_L - 2f, CY - BW - 2f, XF + TL / 2f + 19f, CY + BW + 2f)
            // the blind-spot quarters draw only for a state that shows them
            if (state.showBs) out.union(ZX - 1f, zoneY(true) - 1f, ZX + ZW + 1f, zoneY(false) + 25f)
        }
        if ((parts and PART_PACK) != 0) out.union(BX0 - 1f, CY - BH / 2f - 1f, BX1 + 5f, CY + BH / 2f + 7f)
        if ((parts and PART_MOTORS) != 0) out.union(XR - 31f, CY - 41f, XF + 31f, CY + 35f)
        // the halos, and the shadow of a front tyre at full sag
        if ((parts and PART_TYRES) != 0) out.union(XR - 43f, YT - 34f, XF + 51f, YB + 34f)
        // a psi tag slid clear of the other number on its row reaches the axle (measureText rounds up)
        if ((parts and PART_PRESSURES) != 0) out.union(XR - 2f, YT - 17f, XF + 2f, YB + 17f)
        if ((parts and PART_FLOW) != 0) out.union(XR - 7f, YT + TWD / 2f - 6f, XF + 7f, YB - TWD / 2f + 6f)
        if ((parts and PART_PORT) != 0) out.union(PACK_CX - 7f, CY + BH / 2f - 6f, PACK_CX + 7f, CY + 53f)
    }

    private fun drawDots(c: Canvas, nowMs: Long) {
        val sh = dotSh ?: return
        val sec = nowMs / 1000.0
        for ((si, stream) in streams.withIndex()) {
            if (!active[si] || !shows(if (stream.key == 'c') PART_PORT else PART_FLOW)) continue
            val rev = reverse && (stream.key == 'r' || stream.key == 'f')
            for (route in stream.routes) {
                for (i in 0 until count) {
                    val ph = sec * speed / route.len + hash(i * 7 + stream.key.code)
                    var u = (ph - floor(ph)).toFloat()
                    if (rev) u = 1f - u
                    val a = flowAlpha * smooth(0f, 0.08f, u) * (1f - smooth(0.92f, 1f, u))
                    if (a <= 0.004f) continue
                    route.at(u, pt)
                    val rad = (2.6f + hash(i) * 1.4f) * (0.8f + flowSize * 0.4f)
                    c.unitOval(p, sh, pt[0], pt[1], rad, rad, (a * 255f).roundToInt())
                }
            }
        }
    }

    // Reduce-motion keeps the direction readable with still chevrons instead of moving light.
    private fun buildArrows() {
        arrows.rewind()
        for ((si, stream) in streams.withIndex()) {
            if (!active[si]) continue
            val rev = reverse && (stream.key == 'r' || stream.key == 'f')
            for (route in stream.routes) for (u in ARROW_U) {
                val uu = if (rev) 1f - u else u
                route.at(uu, pt)
                val x0 = pt[0]; val y0 = pt[1]
                route.at((uu + if (rev) -0.02f else 0.02f).coerceIn(0f, 1f), pt)
                val ang = atan2(pt[1] - y0, pt[0] - x0)
                val cs = cos(ang); val sn = sin(ang); val a = 4.5f
                arrows.moveTo(pt[0] - a * cs + a * sn, pt[1] - a * sn - a * cs)
                arrows.lineTo(pt[0], pt[1])
                arrows.lineTo(pt[0] - a * cs - a * sn, pt[1] - a * sn + a * cs)
            }
        }
    }

    // wheels double as the tyre gauges: a low tyre sags and splays at the ground, its rim glows
    private fun drawWheel(c: Canvas, w: Wheel, nowMs: Long) {
        val t = env.theme
        val g = geom.set(w.x, w.y + TH / 2f, TL, TH, w.b.value)
        if (w.builtB != w.b.value || w.builtNight != t.night) {
            w.builtB = w.b.value
            w.builtNight = t.night
            // per-wheel shaders, so a pulsing wheel redraws without re-placing (and rebuilding) a shared one
            if (objectMode) shadeAtRest(w, t) else {
                w.rubber = LinearGradient(0f, 0f, 0f, 1f, RUBBER[if (t.night) 0 else 1], RUBBER_STOPS, Shader.TileMode.CLAMP).apply {
                    m.setScale(1f, g.len); m.postTranslate(0f, g.yt); setLocalMatrix(m)
                }
                val hw = maxOf(g.ht, g.hb + g.bulge)
                w.edge = LinearGradient(0f, 0f, 1f, 1f, intArrayOf(GlassColor.alpha(0xFFFFFFFF.toInt(), if (t.night) 0.42f else 0.55f), GlassColor.alpha(0xFFFFFFFF.toInt(), 0.06f), 0x59000000),
                    EDGE_STOPS, Shader.TileMode.CLAMP).apply { m.setScale(hw * 2f, g.len); m.postTranslate(w.x - hw, g.yt); setLocalMatrix(m) }
            }
            GlassTyres.path(w.top, g)
            GlassTyres.path(w.side, g, 3f - 1.4f * g.low)
            w.tread.rewind()
            for (i in -3..3) {
                val tx = w.x + i * TL * 0.125f
                w.tread.moveTo(tx - 2f, g.yt + 3f); w.tread.lineTo(tx, g.yt + g.len / 2f); w.tread.lineTo(tx - 2f, g.yb - 3f)
            }
        }
        val info = w.info
        val miss = info.kind == GlassTyres.Kind.MISSING
        val lit = info.rank >= 2
        val a = if (info.crit && w.live) pulseAlpha(w, nowMs) else 1f
        val gc = if (info.crit) t.glowDanger else t.glowWarn
        if (!miss) oval(c, shadowSh, w.x + 4f, g.yb - g.len * 0.3f + 4f, (g.hb + g.bulge) * 1.35f, g.len * 0.8f, 1f)
        if (lit) oval(c, if (info.crit) haloDanger else haloWarn, w.x, w.y, TL * 0.95f, TH * 1.35f, if (info.crit) 0.55f + 0.45f * a else 0.8f)
        if (!miss) {
            p.shader = null
            p.color = if (t.night) 0xFF0A0D0C.toInt() else 0xFF161B1A.toInt()
            c.drawPath(w.side, p)
            c.drawPath(w.top, p.shade(w.rubber))
            c.save()
            c.clipPath(w.top)
            s.pathEffect = null; s.shader = null; s.strokeCap = Paint.Cap.BUTT; s.strokeJoin = Paint.Join.MITER
            s.color = 0xD9070908.toInt(); s.strokeWidth = 1.2f
            c.drawPath(w.tread, s)
            c.restore()
            oval(c, specSh, w.x - g.ht * 0.45f, g.yt + g.len * 0.28f, TL * 0.18f, TH * 0.16f, 1f)
            s.shade(w.edge); s.strokeWidth = 1f
            c.drawPath(w.top, s)
            s.shader = null
        }
        if (lit) {
            s.pathEffect = null; s.shader = null; s.strokeJoin = Paint.Join.MITER
            s.color = GlassColor.alpha(GlassColor.lighten(gc, 0.15f), if (info.crit) 0.6f + 0.4f * a else 1f)
            s.strokeWidth = if (info.crit) 1.8f + 1.4f * a else 1.9f
            c.drawPath(w.top, s)
        }
        if (miss) {
            s.shader = null; s.color = t.etch; s.strokeWidth = 1.4f; s.pathEffect = dash
            c.drawPath(w.top, s)
            s.pathEffect = null
        }
    }

    // a lit zone beside the rear quarter, with a small top-down car in it heading the same way
    /** [faint]: the side isn't known, so the zone glows softer and holds no vehicle. */
    private fun drawZone(c: Canvas, t: GlassTheme, left: Boolean, lvl: Int, faint: Boolean = false) {
        if (lvl <= 0) return
        val zy = zoneY(left)
        // the zone's unit-width fade spans it through the canvas scale; its radii scale back to 12
        c.save(); c.translate(ZX, zy); c.scale(ZW, 1f)
        p.shade(if (lvl == 2) zoneDanger else zoneWarn).alpha = ((if (lvl == 2) 0.55f + 0.45f * bsPulse else if (faint) 0.45f else 0.75f) * 255f).roundToInt()
        r.set(0f, 0f, 1f, 24f)
        c.drawRoundRect(r, 12f / ZW, 12f, p)
        c.restore()
        p.shader = null; p.alpha = 255
        if (faint) return
        val gy = zy + 12f
        p.color = if (t.night) 0xFFDDE6E2.toInt() else 0xFF28322F.toInt()
        r.set(GX - 18f, gy - 8f, GX + 18f, gy + 8f); c.drawRoundRect(r, 6.5f, 6.5f, p)
        p.color = if (t.night) 0xFF2B3633.toInt() else 0xFFAFC0BA.toInt()
        c.drawPath(carWindows[if (left) 0 else 1], p)
        p.color = if (t.night) 0xFFB9C6C1.toInt() else 0xFF3C4744.toInt()
        r.set(GX - 7f, gy - 6f, GX + 4f, gy + 6f); c.drawRoundRect(r, 2f, 2f, p)
    }

    private fun tagWidth(tg: Tag) = 1.5f + 9f + tagNum.measureText(tg.num) + 3f + tagUnit.measureText(tg.unit) + 9f + 1.5f

    private fun drawTag(c: Canvas, tg: Tag) {
        val t = env.theme
        tagNum.color = tg.color
        tagUnit.color = GlassColor.alpha(tg.color, 0.7f)
        val wn = tagNum.measureText(tg.num)
        val w = tagWidth(tg)
        val h = 32f
        val l = tg.x - w / 2f; val top = tg.y - h / 2f
        r.set(l, top, l + w, top + h)
        p.shader = null; p.color = GlassColor.alpha(t.glassBot, 0.78f)
        c.drawRoundRect(r, h / 2f, h / 2f, p)
        s.pathEffect = null; s.shader = null; s.color = tg.color; s.strokeWidth = 1.5f
        r.inset(0.75f, 0.75f)
        c.drawRoundRect(r, h / 2f - 0.75f, h / 2f - 0.75f, s)
        // baselines aligned: the number's 24-unit line box sits under 1.5 border + 2 padding
        tagNum.getFontMetrics(fm)
        val base = top + 3.5f + (24f - (fm.descent - fm.ascent)) / 2f - fm.ascent
        c.drawText(tg.num, l + 10.5f, base, tagNum)
        c.drawText(tg.unit, l + 10.5f + wn + 3f, base, tagUnit)
    }

    private fun oval(c: Canvas, sh: Shader, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float) =
        c.unitOval(p, sh, cx, cy, rx, ry, (alpha * 255f).roundToInt())

    private fun shows(part: Int) = (drawParts and part) != 0

    /** Clips away what follows unless [part] draws, so its paint state still carries on; true means restore after it. */
    private fun hideUnless(c: Canvas, part: Int): Boolean {
        if (shows(part)) return false
        c.save(); c.clipRect(0f, 0f, 0f, 0f)
        return true
    }

    /** Spans the unit metal gradient across [r] by moving the canvas, not [sh], so object frames re-place no shader. */
    private fun metalIn(c: Canvas, sh: Shader, vertical: Boolean, rad: Float) {
        sh.setLocalMatrix(null)
        c.save()
        if (vertical) {
            val h = r.height(); c.translate(0f, r.top); c.scale(1f, h); r.top = 0f; r.bottom = 1f
            c.drawRoundRect(r, rad, rad / h, p.shade(sh))
        } else {
            val w = r.width(); c.translate(r.left, 0f); c.scale(w, 1f); r.left = 0f; r.right = 1f
            c.drawRoundRect(r, rad / w, rad, p.shade(sh))
        }
        c.restore()
    }

    private fun cellColor(t: GlassTheme, soc: Int): Int {
        if (soc != cellSoc || cellNight != t.night) {
            cellSoc = soc; cellNight = t.night
            cellMc = GlassColor.alpha(GlassBatteryView.socColor(t, soc.toFloat()), if (t.night) 0.95f else 0.9f)
        }
        return cellMc
    }

    /** Object mode shades a wheel, as drawWheel does, for the sag it settles at: a sagging wheel's frames build no shader. */
    private fun shadeAtRest(w: Wheel, t: GlassTheme) {
        val b = w.b.target
        if (w.restB == b && w.restNight == t.night) return
        w.restB = b; w.restNight = t.night
        val g = (restGeom ?: GlassTyres.Geom().also { restGeom = it }).set(w.x, w.y + TH / 2f, TL, TH, b)
        w.rubber = LinearGradient(0f, 0f, 0f, 1f, RUBBER[if (t.night) 0 else 1], RUBBER_STOPS, Shader.TileMode.CLAMP).apply {
            m.setScale(1f, g.len); m.postTranslate(0f, g.yt); setLocalMatrix(m)
        }
        val hw = maxOf(g.ht, g.hb + g.bulge)
        w.edge = LinearGradient(0f, 0f, 1f, 1f, intArrayOf(GlassColor.alpha(0xFFFFFFFF.toInt(), if (t.night) 0.42f else 0.55f), GlassColor.alpha(0xFFFFFFFF.toInt(), 0.06f), 0x59000000),
            EDGE_STOPS, Shader.TileMode.CLAMP).apply { m.setScale(hw * 2f, g.len); m.postTranslate(w.x - hw, g.yt); setLocalMatrix(m) }
    }

    companion object {
        /** The car's parts, as bits for [drawObject] and [objectBounds]. */
        const val PART_BODY = 1
        const val PART_PACK = 2
        const val PART_MOTORS = 4
        const val PART_TYRES = 8
        const val PART_PRESSURES = 16
        const val PART_FLOW = 32
        const val PART_PORT = 64
        const val PARTS_ALL = 127
        private const val DASH = -1
        private const val NP = 7
        private const val CY = 92f
        // the car sits right of the text: centre 274, never closer than 50 to the right edge
        private const val XF = 350f
        private const val XR = 198f
        private const val YT = 40f
        private const val YB = 144f
        private const val TL = 44f
        private const val TWD = 22f
        private const val TH = 24f
        private const val MARK_GAP = 8f
        private const val TAG_GAP = 7f
        private const val BX0 = XR + 38f
        private const val BX1 = XF - 38f
        private const val BH = 30f
        private const val PACK_CX = (BX0 + BX1) / 2f
        private const val BODY_L = XR - TL / 2f - 16f
        private const val BODY_R = XF + TL / 2f + 14f
        private const val BW = 45f
        private const val EX = XF - 26f
        private const val ZX = BODY_L - 58f
        private const val ZW = XR - BODY_L + 108f
        private const val GX = BODY_L - 26f
        private val AXLES = floatArrayOf(XR, XF)
        private val ENGINE_FINS = floatArrayOf(-7f, -2.3f, 2.3f, 7f)
        private val ARROW_U = floatArrayOf(0.3f, 0.72f)
        private val RUBBER = arrayOf(
            intArrayOf(0xFF1B201F.toInt(), 0xFF4A5351.toInt(), 0xFF343B39.toInt(), 0xFF131716.toInt()),
            intArrayOf(0xFF23292A.toInt(), 0xFF58625F.toInt(), 0xFF3C4442.toInt(), 0xFF1A1F1E.toInt()))
        private val RUBBER_STOPS = floatArrayOf(0f, 0.3f, 0.62f, 1f)
        private val EDGE_STOPS = floatArrayOf(0f, 0.45f, 1f)

        private fun zoneY(left: Boolean) = if (left) CY - BW - 30f else CY + BW + 6f

        /** A faint etched body so the skeleton reads as a car, nose to the right, with windscreen and rear window. */
        private fun bodyOutline(): String {
            val l = BODY_L; val rr = BODY_R; val w = BW
            return "M${l + 20} ${CY - w} H${rr - 34} C${rr - 8} ${CY - w} $rr ${CY - w * 0.55f} $rr $CY" +
                " C$rr ${CY + w * 0.55f} ${rr - 8} ${CY + w} ${rr - 34} ${CY + w} H${l + 20}" +
                " C${l + 4} ${CY + w} $l ${CY + w * 0.6f} $l $CY C$l ${CY - w * 0.6f} ${l + 4} ${CY - w} ${l + 20} ${CY - w} Z" +
                " M${XF - 40} ${CY - w * 0.78f} Q${XF - 26} $CY ${XF - 40} ${CY + w * 0.78f}" +
                " M${XR + 30} ${CY - w * 0.72f} Q${XR + 20} $CY ${XR + 30} ${CY + w * 0.72f}"
        }

        private fun windows(gx: Float, gy: Float) =
            "M${gx + 4} ${gy - 6.2f} L${gx + 10} ${gy - 5} V${gy + 5} L${gx + 4} ${gy + 6.2f} Z" +
                " M${gx - 12} ${gy - 5.4f} L${gx - 8} ${gy - 6} V${gy + 6} L${gx - 12} ${gy + 5.4f} Z"

        fun hash(i: Int): Float { val s = sin(i * 91.7 + 17.3) * 43758.5453; return (s - floor(s)).toFloat() }
        fun smooth(a: Float, b: Float, x: Float): Float { val k = ((x - a) / (b - a)).coerceIn(0f, 1f); return k * k * (3f - 2f * k) }
    }
}
