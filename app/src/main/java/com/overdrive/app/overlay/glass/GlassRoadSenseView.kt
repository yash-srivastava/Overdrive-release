package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * RoadSense — a short road with real-style warning signs beside it; the sign grows and slides down
 * the kerb as the hazard nears, severity shows as glow + painted chevrons, clusters recede along the
 * kerb, and a clear road shrinks to a pill. Driving on a clear road it shows the terrain instead:
 * altitude, the grade underfoot, and the last 2 km of elevation with the car riding its crest.
 * Signs are sprites rendered once per region and kind.
 */
@SuppressLint("ViewConstructor")
class GlassRoadSenseView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, 400f, 184f) {

    enum class Kind(@androidx.annotation.StringRes val labelRes: Int, val picto: String) {
        BREAKER(R.string.glass_hz_breaker, "M-23 9 H-14 C-8 9 -6 -9 0 -9 C6 -9 8 9 14 9 H23 V14 H-23 Z"),
        POTHOLE(R.string.glass_hz_pothole, "M-23 7 H-11 L-8 15 H8 L11 7 H23 V12 H14 L11 20 H-11 L-14 12 H-23 Z M0 -8 A9 9 0 1 1 -0.01 -8 Z M0 -3 A3.4 3.4 0 1 0 0.01 -3 Z"),
        ROUGH(R.string.glass_hz_rough, "M-23 9 C-20 9 -19 -5 -12.5 -5 C-6 -5 -6 9 0 9 C6 9 6 -5 12.5 -5 C19 -5 20 9 23 9 V14 H-23 Z"),
        UNKNOWN(R.string.glass_hz_unknown, "M-3.2 -14 H3.2 L2.2 6 H-2.2 Z M0 9.5 A3.4 3.4 0 1 1 -0.01 9.5 Z"),
    }

    /** Altitude and road grade while driving; [profile] holds the altitudes behind the car every [stepM] metres, oldest first. */
    data class Terrain(val altM: Int, val msl: Boolean, val gradePct: Float?, val profile: List<Float>, val distM: Float, val stepM: Float)

    /** [feed] is false when no hazard data reaches the widget; it then shows only an on/off pill. */
    data class State(
        val active: Boolean, val feed: Boolean, val clear: Boolean, val kind: Kind, val severity: Int,
        val distanceM: Float, val cluster: Int, val keepLeft: Boolean, val region: String,
        /** Shown in place of the pill on a clear road while driving. */
        val terrain: Terrain? = null,
    )

    private var state = State(true, false, true, Kind.BREAKER, 1, 0f, 1, true, "eu")
    private val dist = Spring.number()
    private val clearS = Spring.number(1f)
    private var road = GlassRoad(VPX, 46f, 196f, 88f, lean = 22f)
    private var pulseK = 0f
    private val text = GlassText(context) { env.theme }
    private val pillPaint = GlassFonts.paint(context, 600, 26f, 0)
    private val pillFm = Paint.FontMetrics()
    private var shownD = -1
    private var sprite: Bitmap? = null
    private var spriteRegion = ""
    private var spriteKind: Kind? = null
    private var spriteD = 0f
    /** The road is baked only while the full scene is settled; while it fades to or from the pill it draws live. */
    private var roadBaked = false
    private var bound = false
    // set only inside drawObject: the hazard scene draws alone, without its text, the pill or the terrain
    private var objectMode = false

    // the terrain face: its fade, the car's lean (degrees), the profile sliding left (in steps) and the altitude
    private val terrainS = Spring.number(0f)
    private val tilt = Spring.liquid(0f)
    private val scroll = Spring.number(0f)
    private val altS = Spring.number(0f)
    private val slopeText = GlassText(context) { env.theme }
    private val altText = GlassText(context) { env.theme }
    private var terrain: Terrain? = null
    private var shownAlt = Int.MIN_VALUE
    private var lo = 0f
    private var span = 1f
    private val fadePaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
    private val fadeShader = LinearGradient(PX0 - 4f, 0f, PX0 + FADE, 0f, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
    private val prof = Path()
    private val profFill = Path()
    private var builtFor: Terrain? = null
    private var builtScroll = Float.NaN
    private var fillShader: LinearGradient? = null
    private var fillNight: Boolean? = null
    // the slope scene's shaders, built per theme: road, kerb, body, glass, rim, beam, contact shadow, tail lamp, dot
    private var sceneShaders: Array<Shader>? = null
    private val endPt = FloatArray(2)
    private var poseAt = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path(); private val dash = Path()
    private val verge = Path(); private val asphalt = Path(); private val kerbL = Path(); private val kerbR = Path()
    private var roadShaders: Array<Shader>? = null
    private val postShader = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0xFFC9CFCE.toInt(), 0xFF8E9695.toInt(), 0xFF5E6564.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val m = Matrix()
    private val r = RectF()
    private var glowShader: RadialGradient? = null
    private var glowColor = 0
    private var shadowShader: RadialGradient? = null
    private val us = FloatArray(4)

    init { buildRoad() }

    private val pillOnly get() = state.clear || !state.feed || !state.active
    private val terrainOn get() = pillOnly && state.active && state.terrain != null
    private val pillLabel get() = when { !state.active -> gs(R.string.glass_rs_off); !state.feed -> gs(R.string.glass_rs_on); else -> gs(R.string.glass_rs_clear) }
    private val urgent get() = !pillOnly && (state.distanceM < 100f || state.severity >= 3)

    /** Unchanged data is a no-op. */
    /** Unchanged data is a no-op; [force] re-applies it (a new unit). */
    fun bind(st: State, animate: Boolean, force: Boolean = false) {
        if (!force && bound && st == state) return
        bound = true
        if (force) { shownD = -1; shownAlt = Int.MIN_VALUE }
        val wasPill = pillOnly
        if (st.keepLeft != state.keepLeft) { road = GlassRoad(VPX, 46f, 196f, 88f, lean = 22f, mirror = !st.keepLeft); buildRoad(); layersChanged() }
        state = st
        val instant = !animate || env.reduceMotion
        if (wasPill && !pillOnly) dist.snap(st.distanceM) else dist.set(st.distanceM, instant)
        clearS.set(if (pillOnly) 1f else 0f, instant)
        terrainS.set(if (terrainOn) 1f else 0f, instant)
        if (pillOnly && !terrainOn) setShellSize(PILL_W, PILL_H, true, instant) else setShellSize(baseW, baseH, false, instant)
        st.terrain?.let { bindTerrain(it, instant) }
        text.labelText = gs(st.kind.labelRes) + if (st.cluster > 1) " ×${st.cluster}" else ""
        refreshStatus()
        shownD = -1
        updateDistance()
        syncBaked()
        requestFrames(); invalidate()
    }

    private fun refreshStatus() {
        val t = env.theme
        text.statusText = when (state.severity) { 3 -> gs(R.string.glass_sev_severe); 2 -> gs(R.string.glass_sev_moderate); else -> gs(R.string.glass_sev_minor) }
        text.statusColor = if (state.severity >= 3) t.danger else t.warn
    }

    private fun updateDistance() {
        val d = max(0f, GlassUnits.near(dist.value, env.miles))
        val rounded = if (d >= 100f) (d / 10f).roundToInt() * 10 else (d / 5f).roundToInt() * 5
        if (rounded == shownD) return
        shownD = rounded
        text.setValue(rounded.toString(), GlassUnits.nearUnit(env.miles))
    }

    /** Returns true when the road moved between the baked layer and the live pass. */
    private fun syncBaked(): Boolean {
        val want = !pillOnly && !clearS.moving && clearS.value < 0.01f && !shaping
        if (want == roadBaked) return false
        roadBaked = want
        layersChanged()
        return true
    }

    override fun animate(nowMs: Long, dt: Float): Boolean {
        val a = dist.step(dt); val b = clearS.step(dt)
        val scrolling = scroll.step(dt)
        // the car keeps to the curve as it slides under it, its last step included
        terrain?.let { tr -> if (scroll.value != poseAt) { poseAt = scroll.value; pose(tr) } }
        val tf = terrainS.step(dt) or tilt.step(dt) or scrolling or altS.step(dt)
        updateDistance()
        updateAlt()
        val flipped = syncBaked()
        val pulsing = urgent && env.allows(GlassEnv.Loop.URGENT, stale)
        if (pulsing) { val k = cos(PI * nowMs / PULSE_MS).toFloat(); pulseK = k * k } else pulseK = 0f
        return a || b || tf || flipped || pulsing
    }

    override fun onThemeChanged() {
        glowShader = null; shadowShader = null; roadShaders = null; fillShader = null; sceneShaders = null
        refreshStatus()
        terrain?.let { gradeText(it) }
    }

    // ── the road: paths per side, shaders per theme ──────────────────────────

    private fun buildRoad() {
        road.poly(verge, 0.012f, 1f, 2.3f)
        road.poly(asphalt, 0.012f, 1f)
        road.edge(kerbL, 0.012f, 1f, -1f)
        road.edge(kerbR, 0.012f, 1f, 1f)
        roadShaders = null
    }

    private fun drawRoad(c: Canvas, t: GlassTheme) {
        val ink = t.ink
        val hx = road.fx(VPX)
        val sh = roadShaders ?: arrayOf<Shader>(
            LinearGradient(hx - 110f, 0f, hx + 110f, 0f, intArrayOf(GlassColor.clear(ink), GlassColor.alpha(ink, 0.18f), GlassColor.clear(ink)), null, Shader.TileMode.CLAMP),
            LinearGradient(0f, 46f, 0f, baseH, GlassColor.clear(ink), GlassColor.alpha(ink, 0.05f), Shader.TileMode.CLAMP),
            LinearGradient(0f, 40f, 0f, baseH, GlassColor.alpha(ink, if (t.night) 0.04f else 0.05f), GlassColor.alpha(ink, 0.12f), Shader.TileMode.CLAMP),
        ).also { roadShaders = it }
        p.shade(sh[0]); c.drawRect(hx - 110f, 45.4f, hx + 110f, 46.6f, p)
        p.shade(sh[1]); c.drawPath(verge, p)
        p.shade(sh[2]); c.drawPath(asphalt, p)
        p.shader = null
        s.strokeWidth = 1.4f; s.color = GlassColor.alpha(ink, if (t.night) 0.45f else 0.42f)
        c.drawPath(kerbL, s); c.drawPath(kerbR, s)
    }

    override fun drawBack(c: Canvas) { if (roadBaked) drawRoad(c, env.theme) }

    override fun drawFront(c: Canvas) {}

    override fun drawLive(c: Canvas, nowMs: Long) {
        val t = env.theme
        val fade = clearS.value.coerceIn(0f, 1f)
        if (fade < 0.99f) {
            val sceneAlpha = ((1f - fade) * 255f).roundToInt()
            val layered = sceneAlpha < 255
            if (layered) c.saveLayerAlpha(0f, 0f, baseW, baseH, sceneAlpha)
            if (!roadBaked) drawRoad(c, t)
            drawScene(c, t)
            if (!objectMode) text.draw(c, if (state.keepLeft) 262f else 22f, 0f, baseH)
            if (layered) c.restore()
        }
        if (objectMode) return
        // off the hazard scene: the terrain while driving, else the pill
        val tk = terrainS.value.coerceIn(0f, 1f)
        if (fade * tk > 0.01f) drawTerrain(c, t, fade * tk)
        if (fade * (1f - tk) > 0.01f) drawPill(c, t, fade * (1f - tk))
    }

    /** The road and its hazard alone, for a user widget: the content passes in design units. [nowMs] is uptime. */
    internal fun drawObject(c: Canvas, nowMs: Long) {
        objectMode = true
        try {
            drawBack(c)
            drawLive(c, nowMs)
            if (hasMid) { drawMid(c); drawLiveTop(c, nowMs) }
            drawFront(c)
        } finally { objectMode = false }
    }

    /**
     * Steps everything [tick] does, but stays busy only while the hazard scene moves (distance, urgent pulse) or fades;
     * the terrain, the pill and the text don't count.
     */
    internal fun tickObject(nowMs: Long, dt: Float): Boolean {
        tick(nowMs, dt)
        // the scene draws until it fades into the clear road; a fade toward it counts from wherever it starts
        val shown = clearS.value < 0.99f
        if (clearS.moving && (shown || clearS.target < 0.99f)) return true
        return shown && (dist.moving || (urgent && env.allows(GlassEnv.Loop.URGENT, stale)))
    }

    /** The scene as this widget frames it, for the bound side: sign-side edge to the verge's far corner, farthest glow to the bottom. */
    internal fun objectBounds(out: RectF) {
        // where the verge (2.3 times the road's width) meets the bottom edge, away from the signs
        val far = road.edgeX((baseH - road.yAt(0f)) / (road.yAt(1f) - road.yAt(0f)), 2.3f)
        // a severe cluster's farthest sign glows up to 22.6
        if (state.keepLeft) out.set(0f, 21f, ceil(far + 1f), baseH) else out.set(floor(far - 1f), 21f, baseW, baseH)
    }

    private fun drawScene(c: Canvas, t: GlassTheme) {
        val d = max(0f, dist.value)
        p.shader = null
        p.color = GlassColor.alpha(t.ink, if (t.night) 0.3f else 0.32f)
        c.drawPath(road.dashes(dash, 0.02f, 1f, (1000f - d) * 0.0876f * 4f, 3.2f, 1.3f, 0.025f), p)
        val sev = state.severity.coerceIn(1, 3)
        val gc = if (sev >= 3) t.glowDanger else t.glowWarn
        if (gc != glowColor || glowShader == null) {
            glowColor = gc
            glowShader = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(gc, 0.95f), GlassColor.alpha(gc, 0.5f), GlassColor.clear(gc)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        }
        val shadowSh = shadowShader ?: RadialGradient(0f, 0f, 1f, GlassColor.alpha(t.shadow, t.shadowA), GlassColor.clear(t.shadow), Shader.TileMode.CLAMP).also { shadowShader = it }
        val outward = if (state.keepLeft) -1f else 1f
        val n = state.cluster.coerceIn(1, 4)
        // a cluster recedes along the kerb; each sign keeps about three quarters of its size clear of the one before
        for (j in 0 until n) {
            val u0 = hazardU(d + j * 42f)
            us[j] = if (j == 0) u0 else minOf(u0, us[j - 1] - 0.0052f * (12f + 56f * us[j - 1]))
        }
        // the hazards painted on the road and the severity chevrons lie under every sign
        for (j in n - 1 downTo 0) {
            val u = max(0.03f, us[j])
            drawMark(c, t, road.cAt(u), road.yAt(u), road.hAt(u), u, outward)
        }
        drawChevrons(c, d, sev, gc)
        val spr = spriteFor(state.region, state.kind)
        for (j in n - 1 downTo 0) {
            val u = max(0.03f, us[j])
            val y = road.yAt(u)
            val size = 12f + 56f * u
            val sx = road.edgeX(u, -1f) + outward * (size * 0.52f + 2f + 4f * u)
            val sy = y - size * 0.42f - size * 0.47f
            val pk = if (j == 0) pulseK else 0f
            c.unitOval(p, shadowSh, sx + size * 0.12f, y + 0.5f, size * 0.36f, size * 0.07f + 0.6f)
            val gs = GLOW_K[sev] * size * (1f + 0.12f * pk)
            glowShader?.let { c.unitOval(p, it, sx, sy + size * 0.06f, gs * 0.95f, gs * 0.9f, (GLOW_A[sev] * (if (j == 0) 0.7f + 0.3f * pk else 0.75f) * 255f).roundToInt()) }
            // the post's unit-width metal gradient spans it through the canvas scale; its radius scales back to 1
            val pw = max(1.4f, size * 0.048f); val px = sx - size * 0.024f
            c.save(); c.translate(px, 0f); c.scale(pw, 1f)
            r.set(0f, sy + size * 0.3f, 1f, y)
            c.drawRoundRect(r, 1f / pw, 1f, p.shade(postShader))
            c.restore()
            p.shader = null
            if (spr != null) {
                val k = size / 100f * (1f + 0.04f * pk)
                r.set(sx - SPRITE_HALF * k, sy - SPRITE_HALF * k, sx + SPRITE_HALF * k, sy + SPRITE_HALF * k)
                c.drawBitmap(spr, null, r, bmp)
            }
        }
    }

    private fun drawMark(c: Canvas, t: GlassTheme, cx: Float, y: Float, h: Float, u: Float, outward: Float) {
        when (state.kind) {
            Kind.BREAKER -> {
                val th = 0.6f + 4.2f * u
                for (i in 0 until 10) {
                    val a = cx - h + (2f * h * i) / 10f
                    p.color = if (i % 2 == 1) 0xFF1A1A18.toInt() else 0xFFF2C200.toInt()
                    c.drawRect(a, y - th, a + 2f * h / 10f, y, p)
                }
            }
            Kind.POTHOLE -> {
                val px = cx + outward * h * 0.28f
                r.set(px - (h * 0.3f + 0.8f), y - (h * 0.075f + 0.5f), px + (h * 0.3f + 0.8f), y + (h * 0.075f + 0.5f))
                p.color = 0xFF050707.toInt(); c.drawOval(r, p)
                s.strokeWidth = 0.4f + u; s.color = if (t.night) 0x47FFFFFF else 0xE6FFFFFF.toInt()
                c.drawOval(r, s)
            }
            Kind.ROUGH, Kind.UNKNOWN -> {
                p.color = GlassColor.alpha(if (t.night) 0xFF000000.toInt() else 0xFF1E2A25.toInt(), 0.6f)
                for (k in ROUGH_OFFS.indices step 2) {
                    val ex = cx + ROUGH_OFFS[k] * h; val ey = y + ROUGH_OFFS[k + 1] * h
                    c.drawOval(ex - (h * 0.13f + 0.5f), ey - (h * 0.03f + 0.3f), ex + (h * 0.13f + 0.5f), ey + (h * 0.03f + 0.3f), p)
                }
            }
        }
    }

    private fun drawChevrons(c: Canvas, d: Float, sev: Int, gc: Int) {
        val zh = sceneZ(d)
        for (k in 0 until sev) {
            val zc = zh * (1f - 0.16f * (k + 1))
            if (zc < 0.05f) continue
            val uc = road.uOfZ(zc); val yc = road.yAt(uc); val hc = road.hAt(uc); val cc = road.cAt(uc)
            val w = hc * 0.55f; val rise = hc * 0.16f; val th = hc * 0.06f + 0.4f
            path.rewind()
            path.moveTo(cc - w, yc); path.lineTo(cc, yc - rise); path.lineTo(cc + w, yc)
            path.lineTo(cc + w, yc + th); path.lineTo(cc, yc - rise + th); path.lineTo(cc - w, yc + th); path.close()
            p.color = GlassColor.alpha(gc, 0.9f - k * 0.12f)
            c.drawPath(path, p)
        }
    }

    private fun drawPill(c: Canvas, t: GlassTheme, fade: Float) {
        val a = (fade * 255f).roundToInt()
        val layered = a < 255
        if (layered) c.saveLayerAlpha(0f, 0f, PILL_W, PILL_H, a)
        val label = pillLabel
        pillPaint.color = t.text
        // a label too long for the pill ("RoadSense off") sets a little smaller, clear of the rounded ends
        pillPaint.textSize = PILL_TEXT
        var tw = pillPaint.measureText(label)
        val room = PILL_W - PILL_ICON - 2f * PILL_PAD
        if (tw > room) { pillPaint.textSize = PILL_TEXT * room / tw; tw = room }
        val x0 = (PILL_W - (PILL_ICON + tw)) / 2f
        // the road glyph with its check badge, in the mock's 32-unit icon box drawn 30 wide
        c.save()
        c.translate(x0, PILL_H / 2f - 15f)
        c.scale(30f / 32f, 30f / 32f)
        p.shader = null; p.color = GlassColor.alpha(t.text, 0.22f)
        c.drawPath(GlassPaths.svg(PILL_ROAD), p)
        s.strokeWidth = 1.6f; s.strokeCap = Paint.Cap.ROUND; s.color = GlassColor.alpha(t.text, 0.7f)
        c.drawLine(16f, 7f, 16f, 10f, s); c.drawLine(16f, 14f, 16f, 18f, s); c.drawLine(16f, 22f, 16f, 27f, s)
        p.color = t.glowGood
        c.drawCircle(24f, 10f, 6.5f, p)
        s.strokeWidth = 1.9f; s.color = if (t.night) 0xFF010A07.toInt() else Color.WHITE
        path.rewind(); path.moveTo(21f, 10.2f); path.lineTo(23.2f, 12.4f); path.lineTo(27.2f, 8f)
        c.drawPath(path, s)
        s.strokeCap = Paint.Cap.BUTT
        c.restore()
        pillPaint.getFontMetrics(pillFm)
        c.drawText(label, x0 + 40f, PILL_H / 2f - (pillFm.ascent + pillFm.descent) / 2f, pillPaint)
        if (layered) c.restore()
    }

    // ── terrain ──────────────────────────────────────────────────────────────

    // a new reading: the profile slides on by the road driven since the last one, and the car leans to the new grade
    private fun bindTerrain(tr: Terrain, instant: Boolean) {
        val prev = terrain
        terrain = tr
        val moved = if (prev != null && !instant) ((tr.distM - prev.distM) / tr.stepM).coerceIn(0f, PROFILE_MAX.toFloat()) else 0f
        if (instant) scroll.snap(0f) else if (moved > 0f) { scroll.snap(scroll.value + moved); scroll.set(0f, false) }
        if (prev == null || instant) altS.snap(tr.altM.toFloat()) else altS.set(tr.altM.toFloat(), false)
        // one vertical scale for every road: the line climbs at EXAG times the real grade and a flat road stays
        // flat; only a profile taller than the box squeezes to fit
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in tr.profile) { if (v < mn) mn = v; if (v > mx) mx = v }
        span = max((PY1 - PY0) / (DX / tr.stepM * EXAG), mx - mn)
        lo = (mn + mx) / 2f - span / 2f
        builtFor = null
        pose(tr)
        poseAt = scroll.value
        tilt.set(slopeDeg(tr.gradePct), instant)
        altText.labelText = if (tr.msl) gs(R.string.glass_altitude) else gs(R.string.glass_gps_altitude)
        slopeText.labelText = gs(R.string.glass_slope)
        gradeText(tr)
        shownAlt = Int.MIN_VALUE
        updateAlt()
    }

    // the road leans EXAG_SLOPE times steeper than it is, so a gentle hill still reads, and never past MAX_TILT
    private fun slopeDeg(g: Float?) =
        if (g == null) 0f else Math.toDegrees(atan((g / 100f * EXAG_SLOPE).toDouble())).toFloat().coerceIn(-MAX_TILT, MAX_TILT)

    private fun gradeText(tr: Terrain) {
        val t = env.theme
        val g = tr.gradePct
        when {
            g == null -> { slopeText.setWord("—"); slopeText.statusText = gs(R.string.glass_measuring); slopeText.statusColor = t.text3; slopeText.statusIcon = null }
            abs(g) < LEVEL_PCT -> { slopeText.setValue(pct(abs(g)), "%"); slopeText.statusText = gs(R.string.glass_slope_level); slopeText.statusColor = t.text2; slopeText.statusIcon = null }
            g > 0f -> { slopeText.setValue(pct(g), "%"); slopeText.statusText = gs(R.string.glass_slope_climbing); slopeText.statusColor = t.warn; slopeText.statusIcon = GlassPaths.svg(UP) }
            else -> { slopeText.setValue(pct(-g), "%"); slopeText.statusText = gs(R.string.glass_slope_descending); slopeText.statusColor = t.regenText; slopeText.statusIcon = GlassPaths.svg(DOWN) }
        }
        // RoadSense keeps watching the road ahead
        altText.statusText = pillLabel
        altText.statusColor = t.good
        altText.statusIcon = GlassPaths.svg(DOT)
    }

    private fun pct(g: Float) = if (g < 9.95f) "${(g * 10f).roundToInt() / 10f}" else "${g.roundToInt()}"

    private fun updateAlt() {
        if (terrain == null) return
        val a = GlassUnits.height(altS.value, env.miles).roundToInt()
        if (a == shownAlt) return
        shownAlt = a
        altText.setValue(a.toString(), GlassUnits.heightUnit(env.miles))
    }

    // reading i of n across the profile box, newest at its right edge; a scroll still to run draws it further right
    private fun px(i: Int, n: Int) = PX1 - (n - 1 - i - scroll.value) * DX
    private fun py(v: Float) = PY1 - (v - lo) / span * (PY1 - PY0)

    /** The profile's "you are here" at its right edge, on the line as it slides ([endPt]). */
    private fun pose(tr: Terrain) {
        val v = tr.profile
        val n = v.size
        endPt[0] = PX1
        if (n < 2) { endPt[1] = if (n == 1) py(v[0]) else (PY0 + PY1) / 2f; return }
        val f = (n - 1 - scroll.value).coerceIn(0f, (n - 1).toFloat())
        val i0 = floor(f).toInt().coerceIn(0, n - 2)
        val k = f - i0
        endPt[1] = py(v[i0]) + (py(v[i0 + 1]) - py(v[i0])) * k
    }

    // a smooth line through the midpoints of the readings, and the area under it
    private fun buildProfile(tr: Terrain) {
        if (tr === builtFor && scroll.value == builtScroll) return
        builtFor = tr; builtScroll = scroll.value
        prof.rewind(); profFill.rewind()
        val v = tr.profile
        val n = v.size
        if (n < 2) return
        var x0 = px(0, n); var y0 = py(v[0])
        prof.moveTo(x0, y0)
        for (i in 1 until n) {
            val x1 = px(i, n); val y1 = py(v[i])
            prof.quadTo(x0, y0, (x0 + x1) / 2f, (y0 + y1) / 2f)
            x0 = x1; y0 = y1
        }
        prof.lineTo(x0, y0)
        profFill.set(prof)
        profFill.lineTo(x0, PY1 + FILL_DEPTH)
        profFill.lineTo(px(0, n), PY1 + FILL_DEPTH)
        profFill.close()
    }

    private fun ensureSceneShaders(t: GlassTheme): Array<Shader> {
        sceneShaders?.let { return it }
        val white = 0xFFFFFFFF.toInt()
        val half = ROAD_L / 2f
        val ends = floatArrayOf(0f, 0.2f, 0.8f, 1f)
        val asphalt = if (t.night) 0xE62A3230.toInt() else 0xD9596360.toInt()
        val kerb = GlassColor.alpha(white, if (t.night) 0.6f else 0.95f)
        val body = if (t.night) intArrayOf(0xFFF4F7F8.toInt(), 0xFFBCC5C7.toInt(), 0xFF7E898B.toInt()) else intArrayOf(0xFF717B83.toInt(), 0xFF434B52.toInt(), 0xFF262C31.toInt())
        return arrayOf<Shader>(
            LinearGradient(-half, 0f, half, 0f, intArrayOf(GlassColor.clear(asphalt), asphalt, asphalt, GlassColor.clear(asphalt)), ends, Shader.TileMode.CLAMP),
            LinearGradient(-half, 0f, half, 0f, intArrayOf(GlassColor.clear(kerb), kerb, kerb, GlassColor.clear(kerb)), ends, Shader.TileMode.CLAMP),
            LinearGradient(0f, -23.5f, 0f, -4.5f, body, floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP),
            LinearGradient(-20f, -22f, 15f, -15f, intArrayOf(0xFF2E4248.toInt(), 0xFF0E171A.toInt(), 0xFF22323A.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP),
            RadialGradient(-0.8f, -0.8f, 3.6f, intArrayOf(0xFFE9EEF0.toInt(), 0xFF8E989C.toInt(), 0xFF4A5357.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP),
            LinearGradient(31f, 0f, 92f, 0f, GlassColor.alpha(0xFFFFF1CC.toInt(), if (t.night) 0.55f else 0.4f), GlassColor.clear(0xFFFFF1CC.toInt()), Shader.TileMode.CLAMP),
            RadialGradient(0f, 0f, 1f, GlassColor.alpha(t.shadow, t.shadowA), GlassColor.clear(t.shadow), Shader.TileMode.CLAMP),
            RadialGradient(0f, 0f, 1f, GlassColor.alpha(0xFFFF3B30.toInt(), 0.75f), GlassColor.clear(0xFFFF3B30.toInt()), Shader.TileMode.CLAMP),
            RadialGradient(0f, 0f, 1f, GlassColor.alpha(t.glowAccent, 0.6f), GlassColor.clear(t.glowAccent), Shader.TileMode.CLAMP),
        ).also { sceneShaders = it }
    }

    private fun drawTerrain(c: Canvas, t: GlassTheme, alpha: Float) {
        val tr = terrain ?: return
        val a = (alpha * 255f).roundToInt()
        val layered = a < 255
        if (layered) c.saveLayerAlpha(0f, 0f, baseW, baseH, a)
        val sh = ensureSceneShaders(t)
        slopeText.draw(c, 22f, TEXT_TOP, TEXT_BOTTOM)
        altText.draw(c, PX0, TEXT_TOP, TEXT_BOTTOM)
        drawSlope(c, t, sh)
        drawProfile(c, t, tr, sh)
        if (layered) c.restore()
    }

    // the road under the car, leaning with the grade, and the car on it with its lamps lighting the way ahead
    private fun drawSlope(c: Canvas, t: GlassTheme, sh: Array<Shader>) {
        c.save()
        c.clipRect(4f, SLOPE_TOP, SLOPE_RIGHT, baseH)
        c.translate(SCX, SCY)
        c.rotate(-tilt.value)
        val half = ROAD_L / 2f
        p.shade(sh[S_ROAD]); c.drawRect(-half, 0f, half, ROAD_T, p)
        p.shade(sh[S_KERB]); c.drawRect(-half, -0.6f, half, 1f, p)
        p.shader = null
        // the lane line, fading out toward the road's ends
        for (k in -5..5) {
            val x = k * 17f
            val f = 1f - (abs(x) / half).let { it * it }
            if (f <= 0.02f) continue
            p.color = GlassColor.alpha(t.ink, (if (t.night) 0.42f else 0.6f) * f)
            c.drawRect(x - 4.5f, ROAD_T / 2f - 0.7f, x + 4.5f, ROAD_T / 2f + 0.7f, p)
        }
        c.scale(CAR_S, CAR_S)
        drawCarSide(c, t, sh)
        c.restore()
    }

    // a side-on saloon facing the way the road goes, ground at y 0: beam, shadow, body, glass, lamps, wheels
    private fun drawCarSide(c: Canvas, t: GlassTheme, sh: Array<Shader>) {
        p.shade(sh[S_BEAM]); c.drawPath(GlassPaths.svg(BEAM), p)
        c.unitOval(p, sh[S_SHADOW], 0f, 0f, 34f, 3.4f)
        p.shade(sh[S_BODY]); c.drawPath(GlassPaths.svg(BODY), p)
        p.shade(sh[S_GLASS]); c.drawPath(GlassPaths.svg(WINDOWS), p)
        p.shader = null
        // a lit shoulder line down the flank
        s.shader = null; s.strokeCap = Paint.Cap.ROUND; s.strokeWidth = 0.9f
        s.color = GlassColor.alpha(0xFFFFFFFF.toInt(), if (t.night) 0.6f else 0.35f)
        c.drawLine(-26f, -14.8f, 25.5f, -14.2f, s)
        s.strokeCap = Paint.Cap.BUTT
        // white at the nose, red at the tail
        p.color = 0xFFFFF4D6.toInt()
        c.drawRoundRect(29.2f, -12.8f, 32.2f, -10.2f, 1.2f, 1.2f, p)
        c.unitOval(p, sh[S_TAIL], -30.6f, -11.6f, 6.5f, 4.5f)
        p.shader = null; p.color = 0xFFFF4136.toInt()
        c.drawRoundRect(-31.8f, -13f, -29.6f, -10.2f, 1f, 1f, p)
        val tyre = if (t.night) 0xFF0B0F0E.toInt() else 0xFF1C2124.toInt()
        for (wx in WHEEL_X) {
            p.shader = null; p.color = tyre
            c.drawCircle(wx, -5.6f, 5.6f, p)
            c.save(); c.translate(wx, -5.6f)
            p.shade(sh[S_RIM]); c.drawCircle(0f, 0f, 3.5f, p)
            c.restore()
            p.shader = null; p.color = tyre
            c.drawCircle(wx, -5.6f, 1.1f, p)
        }
    }

    private fun drawProfile(c: Canvas, t: GlassTheme, tr: Terrain, sh: Array<Shader>) {
        buildProfile(tr)
        if (fillNight != t.night || fillShader == null) {
            fillNight = t.night
            fillShader = LinearGradient(0f, PY0, 0f, PY1 + FILL_DEPTH,
                GlassColor.alpha(t.glowAccent, if (t.night) 0.34f else 0.26f), GlassColor.clear(t.glowAccent), Shader.TileMode.CLAMP)
        }
        // the road behind, fading in from the left and ending where the car is now
        val layer = c.saveLayer(PX0 - 4f, PY0 - 30f, PX1 + 1f, baseH, null)
        c.clipRect(PX0 - 4f, PY0 - 30f, PX1 + 1f, baseH)
        p.shade(fillShader); c.drawPath(profFill, p); p.shader = null
        s.shader = null; s.strokeCap = Paint.Cap.ROUND
        s.strokeWidth = 6f; s.color = GlassColor.alpha(t.glowAccent, 0.16f); c.drawPath(prof, s)
        s.strokeWidth = 2f; s.color = t.glowAccent; c.drawPath(prof, s)
        s.strokeCap = Paint.Cap.BUTT
        fadePaint.shader = fadeShader
        c.drawRect(PX0 - 4f, PY0 - 30f, PX0 + FADE, baseH, fadePaint)
        c.restoreToCount(layer)
        // you are here
        c.unitOval(p, sh[S_DOT], endPt[0], endPt[1], 11f, 11f)
        p.shader = null
        p.color = t.glowAccent; c.drawCircle(endPt[0], endPt[1], 3.4f, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(endPt[0], endPt[1], 1.4f, p)
    }

    // ── sign sprites ─────────────────────────────────────────────────────────

    private fun spriteFor(region: String, kind: Kind): Bitmap? {
        sprite?.let { if (region == spriteRegion && kind == spriteKind && d == spriteD) return it }
        spriteRegion = region; spriteKind = kind; spriteD = d
        sprite?.recycle()
        val px = (SPRITE_HALF * 2f * d).roundToInt().coerceAtLeast(16)
        sprite = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
            // far signs are drawn at a small fraction of this size; mipmaps keep them from shimmering
            b.setHasMipMap(true)
            val sc = Canvas(b)
            sc.scale(px / (SPRITE_HALF * 2f), px / (SPRITE_HALF * 2f))
            sc.translate(SPRITE_HALF, SPRITE_HALF)
            drawSign(sc, region, kind)
        }
        return sprite
    }

    /** One sign in its own units (the triangle spans ±46). Fill before stroke, like SVG. */
    private fun drawSign(c: Canvas, region: String, kind: Kind) {
        val dark = 0xFF3A3F40.toInt()
        val ink = 0xFF141414.toInt()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        val pic = GlassPaths.svg(kind.picto).apply { fillType = Path.FillType.EVEN_ODD }
        if (region == "us") {
            val dia = GlassPaths.svg(DIA)
            c.save(); c.translate(2.5f, 3f); fill.color = dark; stroke.color = dark; stroke.strokeWidth = 10f
            c.drawPath(dia, fill); c.drawPath(dia, stroke); c.restore()
            fill.shade(bboxGradient(-46f, -46f, 92f, 92f, 0xFFFFDB4D.toInt(), 0xFFE0A800.toInt()))
            c.drawPath(dia, fill); fill.shader = null
            stroke.color = 0xFFF4C400.toInt(); c.drawPath(dia, stroke)
            stroke.color = ink; stroke.strokeWidth = 4.5f; c.drawPath(GlassPaths.svg(DIA_IN), stroke)
            c.save(); c.translate(0f, 1f); c.scale(1.05f, 1.05f); fill.color = ink; c.drawPath(pic, fill); c.restore()
            return
        }
        val cn = region == "cn"
        val tri = GlassPaths.svg(TRI)
        c.save(); c.translate(2.5f, 3f); fill.color = dark; stroke.color = dark; stroke.strokeWidth = 15f
        c.drawPath(tri, fill); c.drawPath(tri, stroke); c.restore()
        val face = bboxGradient(-46f, -44f, 92f, 80f, if (cn) 0xFFFFD640.toInt() else Color.WHITE, if (cn) 0xFFE0A600.toInt() else 0xFFD3D9D8.toInt())
        // outer border, the face over its inner half, then the inner border (SVG's fill-then-stroke, twice)
        stroke.strokeWidth = 15f; stroke.color = if (cn) 0xFFF2BE00.toInt() else 0xFFF4F5F2.toInt(); c.drawPath(tri, stroke)
        fill.shade(face); c.drawPath(tri, fill); fill.shader = null
        stroke.strokeWidth = if (cn) 7f else 10f; stroke.color = if (cn) ink else 0xFFC8102E.toInt(); c.drawPath(tri, stroke)
        c.save(); c.translate(0f, 14f); c.scale(0.92f, 0.92f); fill.color = ink; c.drawPath(pic, fill); c.restore()
    }

    /** SVG objectBoundingBox gradient (0,0)→(1,1) over the given box. */
    private fun bboxGradient(x: Float, y: Float, w: Float, h: Float, c0: Int, c1: Int) =
        LinearGradient(0f, 0f, 1f, 1f, c0, c1, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w, h); postTranslate(x, y) })
        }

    private fun hazardU(dm: Float) = road.uOfZ(sceneZ(dm))

    companion object {
        const val PILL_W = 214f
        const val PILL_H = 64f
        // the terrain face: two text columns over the road's slope (left) and the elevation behind (right)
        private const val TEXT_TOP = 8f
        private const val TEXT_BOTTOM = 104f
        private const val PX0 = 214f
        private const val PX1 = 376f
        private const val PY0 = 120f
        private const val PY1 = 164f
        /** 2 km of 25 m readings across the profile. */
        private const val PROFILE_MAX = 80
        private const val DX = (PX1 - PX0) / (PROFILE_MAX - 1)
        private const val FILL_DEPTH = 16f
        /** How much steeper than the real grade the profile draws. */
        private const val EXAG = 6f
        private const val FADE = 30f
        // the slope scene: the road's centre, its length and depth, the car's size, and how far it leans
        private const val SCX = 104f
        private const val SCY = 152f
        private const val SLOPE_TOP = 100f
        private const val SLOPE_RIGHT = 200f
        private const val ROAD_L = 176f
        private const val ROAD_T = 11f
        private const val CAR_S = 1.12f
        private const val EXAG_SLOPE = 4f
        private const val MAX_TILT = 28f
        private const val LEVEL_PCT = 1f
        private val WHEEL_X = floatArrayOf(-19.2f, 19.2f)
        private const val S_ROAD = 0
        private const val S_KERB = 1
        private const val S_BODY = 2
        private const val S_GLASS = 3
        private const val S_RIM = 4
        private const val S_BEAM = 5
        private const val S_SHADOW = 6
        private const val S_TAIL = 7
        private const val S_DOT = 8
        // a low saloon side-on, nose to the right, ground at y 0, wheels at ±19.2
        private const val BODY = "M-31 -6.5 C-32 -9.5 -31 -12.5 -28 -13.5 L-22 -14.5 C-18 -19.5 -12 -23 -6 -23.5 L4 -23.5 C9 -23.1 13 -20.5 17 -16.5 L26 -14.7 C29.5 -13.9 31.5 -12 31.8 -9 L32 -6.5 C31.5 -5.3 30 -4.9 28.5 -4.9 L25.8 -4.9 A6.6 6.6 0 0 0 12.6 -4.9 L-12.6 -4.9 A6.6 6.6 0 0 0 -25.8 -4.9 L-29 -4.9 C-30.3 -4.9 -31 -5.5 -31 -6.5 Z"
        private const val WINDOWS = "M-19.6 -15.2 C-16 -19.2 -11.5 -21.8 -6.2 -22.2 L-1 -22.2 L-1 -15.2 Z M1 -22.2 L3.8 -22.2 C8 -21.9 11.2 -20 14.8 -16.4 L15.2 -15.2 L1 -15.2 Z"
        private const val BEAM = "M31.5 -12.8 L92 -17 L92 0 L31.5 -10.2 Z"
        // the status lines' marks, in GlassText's 11×15 icon box: climb, descent, RoadSense watching
        private const val UP = "M5.5 2.5 L11 12.5 H0 Z"
        private const val DOWN = "M0 2.5 H11 L5.5 12.5 Z"
        private const val DOT = "M5.5 4.5 A3.5 3.5 0 1 1 5.49 4.5 Z"
        // the 30-unit road glyph and its 10-unit gap to the label; the least margin to each rounded end
        private const val PILL_ICON = 40f
        private const val PILL_PAD = 20f
        private const val PILL_TEXT = 26f
        private const val VPX = 190f
        private const val PULSE_MS = 600.0
        /** Half the sprite's side in sign units: covers the ±46 sign plus its 7.5 border and drop shadow. */
        private const val SPRITE_HALF = 60f
        private val GLOW_K = floatArrayOf(0f, 1f, 1.18f, 1.34f)
        private val GLOW_A = floatArrayOf(0f, 0.5f, 0.72f, 0.9f)
        private val ROUGH_OFFS = floatArrayOf(-0.5f, 0f, 0.1f, -0.035f, 0.45f, 0.02f, -0.15f, 0.05f)
        private const val TRI = "M0 -44 L46 36 L-46 36 Z"
        private const val DIA = "M0 -46 L46 0 L0 46 L-46 0 Z"
        private const val DIA_IN = "M0 -39 L39 0 L0 39 L-39 0 Z"
        private const val PILL_ROAD = "M11 29 L14.5 4 H17.5 L21 29 Z"

        /** Real distance (m) → compressed scene depth, so a hazard 500 m out still sits visibly on the road. */
        fun sceneZ(dm: Float) = 24.35f * dm / (dm + 278f) + 0.35f
    }
}
