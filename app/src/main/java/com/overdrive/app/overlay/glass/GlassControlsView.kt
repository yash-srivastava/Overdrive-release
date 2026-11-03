package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.roundToInt

/**
 * Vehicle controls — glass keys whose objects show state (padlock shackle, fan, boot lid, lamp) and
 * a Close / Vent / Open switch for all four windows. Keys work only when parked. Each key and the
 * windows panel bake when still and draw live only while they animate; the fan's rotor stays live.
 */
@SuppressLint("ViewConstructor")
class GlassControlsView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, 620f, 184f) {

    enum class Windows { CLOSED, VENT, OPEN }
    enum class Command { LOCK, UNLOCK, CLIMATE_ON, CLIMATE_OFF, BOOT, FLASH, WINDOWS_CLOSED, WINDOWS_VENT, WINDOWS_OPEN }

    /** Null fields are not reported by the car: lock, climate and windows then show no state, and the boot is an action. */
    data class State(
        val locked: Boolean? = null,
        val climate: Boolean? = null,
        val cabinC: Int? = null,
        val boot: Boolean? = null,
        val windows: Windows? = null,
        /** The driver door alone: decides the lock key's action, never its whole-car caption. */
        val driverLocked: Boolean? = null,
    )

    /** Sends a command; the host calls back on the main thread with whether the car accepted it. */
    var onCommand: ((Command, (Boolean) -> Unit) -> Unit)? = null

    private var state = State()
    private val lockS = Spring.liquid(1f)
    private val bootS = Spring.liquid(0f)
    private val flashS = Spring.number(0f)
    private val dropS = Spring(0f, 5f, 1f)
    private var fanAngle = 0f
    private val pending = BooleanArray(4)
    // a window command or a flash on its way (a cloud command can take 30 s): another tap would send it again
    private var pendingSeg = -1
    private var pendingFlash = false
    private var pressed = -1
    private var pressUntil = 0L
    private var downTarget = -1
    private var blinkStep = -1
    private var blinkAt = 0L
    // cells 0-3 are the keys, 4 the windows panel; a live cell is left out of the baked layer
    private val live = BooleanArray(5)
    private var liveMask = 0
    private var bakedParked = true
    private var bound = false

    private val captions = arrayOf(gs(R.string.glass_ctl_locked), gs(R.string.glass_ctl_climate), gs(R.string.glass_ctl_boot), gs(R.string.glass_ctl_flash))
    // every icon part's sprite, held here so drawing never builds a cache key
    private val shackleSlot = GlassGlyphs.Slot()
    private val lockSlot = GlassGlyphs.Slot()
    private val ringSlot = GlassGlyphs.Slot()
    private val rotorSlots = Array(ROTOR_FRAMES) { GlassGlyphs.Slot() }
    private val bootSlot = GlassGlyphs.Slot()
    private val lidSlot = GlassGlyphs.Slot()
    private val lampSlot = GlassGlyphs.Slot()
    private val lampLitSlot = GlassGlyphs.Slot()
    private val beamsSlot = GlassGlyphs.Slot()
    private val beamsLitSlot = GlassGlyphs.Slot()
    private val carSlot = GlassGlyphs.Slot()
    private val capPaint = GlassFonts.paint(context, 600, 15f, 0).apply { textAlign = Paint.Align.CENTER }
    private val fm = Paint.FontMetrics()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()
    private val r = RectF()
    private val b = RectF()

    // geometry, in design units
    private val shackle = Path().apply {
        val cx = keyCx(0)
        moveTo(cx - 9f, CY - 3f); lineTo(cx - 9f, CY - 11f)
        arcTo(RectF(cx - 9f, CY - 20f, cx + 9f, CY - 2f), 180f, 180f, false)
        lineTo(cx + 9f, CY - 3f)
    }
    private val blade = GlassPaths.svg("M0 0 C4 -6 3 -15 -2 -16 C-5 -10 -3 -4 0 0 Z")
    private val bootBody: Path
    private val bootOpening: Path
    private val lid: Path
    private val lamp: Path
    private val beams: Path
    private val carBody: Path
    private val winPaths = Array(2) { Path() }
    private val air = Path()

    init {
        val bx = keyCx(2) - 17f; val by = CY + 9f
        bootBody = GlassPaths.svg("M${bx - 4} $by V${by - 9} L${bx + 4} ${by - 20} H${bx + 24} L${bx + 30} ${by - 9} V$by Z")
        bootOpening = GlassPaths.svg("M${bx + 23} ${by - 18} L${bx + 28.5f} ${by - 8} V${by - 2} H${bx + 24} Z")
        lid = GlassPaths.svg("M${bx + 24} ${by - 20} L${bx + 30.5f} ${by - 9} V${by - 2}")
        val fx = keyCx(3)
        lamp = GlassPaths.svg("M${fx - 14} ${CY - 11} C${fx - 2} ${CY - 14} ${fx + 4} ${CY - 6} ${fx + 4} $CY C${fx + 4} ${CY + 6} ${fx - 2} ${CY + 14} ${fx - 14} ${CY + 11} Z")
        beams = GlassPaths.svg("M${fx + 9} ${CY - 9} l10 -3 M${fx + 10} $CY h11 M${fx + 9} ${CY + 9} l10 3")
        carBody = GlassPaths.svg(
            "M${wx(-0.48f)} ${wy(0f)} H${wx(0.47f)} Q${wx(0.5f)} ${wy(0f)} ${wx(0.5f)} ${wy(5f)} V${wy(11f)} Q${wx(0.49f)} ${wy(15f)} ${wx(0.4f)} ${wy(16f)}" +
                " L${wx(0.22f)} ${wy(17f)} L${wx(0.06f)} ${wy(34f)} H${wx(-0.26f)} L${wx(-0.42f)} ${wy(18f)} L${wx(-0.47f)} ${wy(16f)}" +
                " Q${wx(-0.5f)} ${wy(15f)} ${wx(-0.5f)} ${wy(10f)} V${wy(5f)} Q${wx(-0.5f)} ${wy(0f)} ${wx(-0.48f)} ${wy(0f)} Z")
        val w0 = winPaths[0]
        w0.moveTo(wx(0.19f), wy(W_BOT)); w0.lineTo(wx(0.05f), wy(W_TOP)); w0.lineTo(wx(-0.07f), wy(W_TOP)); w0.lineTo(wx(-0.07f), wy(W_BOT)); w0.close()
        val w1 = winPaths[1]
        w1.moveTo(wx(-0.11f), wy(W_BOT)); w1.lineTo(wx(-0.11f), wy(W_TOP)); w1.lineTo(wx(-0.24f), wy(W_TOP)); w1.lineTo(wx(-0.38f), wy(W_BOT)); w1.close()
        for (f in floatArrayOf(-0.22f, 0f)) {
            val x = wx(f); val y = wy(39f)
            air.moveTo(x, y); air.quadTo(x + 4f, y - 4f, x + 8f, y); air.quadTo(x + 12f, y + 4f, x + 16f, y)
        }
    }

    // theme shaders, unit-sized and placed on each element's bounding box like SVG objectBoundingBox fills
    private var shaderNight: Boolean? = null
    private lateinit var keyBody: LinearGradient
    private lateinit var rimSh: LinearGradient
    private lateinit var metal: LinearGradient
    private lateinit var glass: LinearGradient
    private lateinit var shadowSh: RadialGradient
    private lateinit var glowAccent: RadialGradient
    private lateinit var glowWarn: RadialGradient

    private val parked get() = !env.driving
    private val spinning get() = state.climate == true && parked && env.allows(GlassEnv.Loop.AMBIENT, stale)

    /** Unchanged data is a no-op. */
    fun bind(st: State, animate: Boolean) {
        if (bound && st == state) return
        bound = true
        val changed = st != state
        state = st
        val instant = !animate || env.reduceMotion
        lockS.set(if (st.locked == false) 0f else 1f, instant)
        bootS.set(if (st.boot == true) 1f else 0f, instant)
        // vent is the car's own small crack; the panes move slowly, like real glass
        dropS.set(when (st.windows) { Windows.VENT -> 0.28f; Windows.OPEN -> 0.92f; else -> 0f }, instant)
        captions[0] = when (st.locked) { true -> gs(R.string.glass_ctl_locked); false -> gs(R.string.glass_ctl_unlocked); null -> if (st.driverLocked == true) gs(R.string.glass_ctl_unlock) else gs(R.string.glass_ctl_lock) }
        captions[1] = when (st.climate) { true -> st.cabinC?.let { gs(R.string.glass_ctl_cooling, it) } ?: gs(R.string.glass_ctl_climate_on); else -> gs(R.string.glass_ctl_climate) }
        captions[2] = when (st.boot) { null -> gs(R.string.glass_ctl_open_boot); true -> gs(R.string.glass_ctl_boot_open); false -> gs(R.string.glass_ctl_boot) }
        syncLive(SystemClock.uptimeMillis())
        if (changed) layersChanged()
        requestFrames()
    }

    // ── input ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = (e.x - shellLeftPx) / d
        val y = (e.y - shellTopPx) / d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!parked || stale) return false
                downTarget = hit(x, y)
                if (downTarget < 0) return false
                if (downTarget < 4) press(downTarget)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val t = downTarget
                downTarget = -1
                // the car may have left Park while the finger was down
                if (t >= 0 && hit(x, y) == t && parked && !stale) { performClick(); tap(t) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { downTarget = -1; return true }
        }
        return downTarget >= 0
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /** 0-3 keys, 4-6 the Close / Vent / Open segments, -1 nothing. */
    private fun hit(x: Float, y: Float): Int {
        for (i in 0 until 4) {
            val kx = keyX(i)
            if (x >= kx && x <= kx + KS && y >= KY && y <= KY + KS) return i
        }
        if (y >= SEG_Y && y <= SEG_Y + SEG_H) for (i in 0 until 3) {
            val sx = segX(i)
            if (x >= sx && x <= sx + SEG_W) return 4 + i
        }
        return -1
    }

    private fun press(i: Int) {
        pressed = i
        pressUntil = SystemClock.uptimeMillis() + PRESS_MS
        syncLive(SystemClock.uptimeMillis())
        requestFrames()
    }

    private fun tap(t: Int) {
        val send = onCommand
        if (t >= 4) {
            if (pendingSeg >= 0) return
            val w = Windows.entries[t - 4]
            val cmd = when (w) { Windows.CLOSED -> Command.WINDOWS_CLOSED; Windows.VENT -> Command.WINDOWS_VENT; Windows.OPEN -> Command.WINDOWS_OPEN }
            if (send == null) { bind(state.copy(windows = w), true); return }
            pendingSeg = t - 4
            requestFrames(); invalidate()
            send(cmd) { ok ->
                pendingSeg = -1
                if (ok) bind(state.copy(windows = w), true)
                invalidate(); requestFrames()
            }
            return
        }
        if (t == 3) {
            if (pendingFlash) return
            if (send != null) { pendingFlash = true; send(Command.FLASH) { pendingFlash = false } }
            blinkStep = 0; blinkAt = SystemClock.uptimeMillis()
            requestFrames()
            return
        }
        if (pending[t]) return
        pending[t] = true
        // the driver door stands in for an unknown whole-car state; nothing known locks: the safe direction
        val cmd = when (t) {
            0 -> if ((state.locked ?: state.driverLocked) == true) Command.UNLOCK else Command.LOCK
            1 -> if (state.climate == true) Command.CLIMATE_OFF else Command.CLIMATE_ON
            else -> Command.BOOT
        }
        val done = { ok: Boolean ->
            pending[t] = false
            // applied to the state as it is when the car answers, so another key's result isn't undone
            if (ok) bind(when (cmd) {
                Command.LOCK -> state.copy(locked = true)
                Command.UNLOCK -> state.copy(locked = false)
                Command.CLIMATE_ON -> state.copy(climate = true)
                Command.CLIMATE_OFF -> state.copy(climate = false)
                else -> state.copy(boot = state.boot?.let { true })
            }, true)
            invalidate()
            requestFrames()
        }
        requestFrames()
        invalidate()
        if (send == null) postDelayed({ done(true) }, PENDING_MS) else send(cmd, done)
    }

    // ── motion ───────────────────────────────────────────────────────────────

    override fun animate(nowMs: Long, dt: Float): Boolean {
        var busy = false
        if (lockS.step(dt)) busy = true
        if (bootS.step(dt)) busy = true
        if (dropS.step(dt)) busy = true
        if (blinkStep >= 0 && nowMs >= blinkAt) {
            flashS.set(if (blinkStep % 2 == 0) 1f else 0f, env.reduceMotion)
            blinkStep++; blinkAt += BLINK_MS
            if (blinkStep > 6) { flashS.set(0f, env.reduceMotion); blinkStep = -1 }
        }
        if (blinkStep >= 0) busy = true
        if (flashS.step(dt)) busy = true
        if (pressed >= 0 && nowMs >= pressUntil) { pressed = -1; busy = true }
        if (pressed >= 0) busy = true
        if (spinning) { fanAngle = (fanAngle + dt * 120f) % 360f; busy = true }
        if ((pending.any { it } || pendingSeg >= 0) && env.allows(GlassEnv.Loop.AMBIENT, stale)) busy = true
        if (syncLive(nowMs)) busy = true
        return busy
    }

    private fun syncLive(now: Long): Boolean {
        // a pending key stays baked: only its ring moves, drawn over it in drawLive
        live[0] = lockS.moving || pressed == 0
        live[1] = pressed == 1
        live[2] = bootS.moving || pressed == 2
        live[3] = flashS.moving || blinkStep >= 0 || pressed == 3
        live[4] = dropS.moving
        var mask = 0
        for (i in live.indices) if (live[i]) mask = mask or (1 shl i)
        if (mask == liveMask) return false
        liveMask = mask
        layersChanged()
        return true
    }

    override fun envChanged() {
        if (parked != bakedParked) { bakedParked = parked; layersChanged() }
        super.envChanged()
    }

    override fun onThemeChanged() { shaderNight = null }

    // ── drawing ──────────────────────────────────────────────────────────────

    private fun ensureShaders(t: GlassTheme) {
        if (shaderNight == t.night) return
        shaderNight = t.night
        val night = t.night
        val white = 0xFFFFFFFF.toInt()
        keyBody = LinearGradient(0f, 0f, 0.6f, 1f,
            if (night) intArrayOf(GlassColor.alpha(white, 0.13f), GlassColor.alpha(white, 0.04f), 0x40000000) else intArrayOf(white, 0xFFF1F5F2.toInt(), 0xFFD9E1DC.toInt()),
            floatArrayOf(0f, if (night) 0.45f else 0.5f, 1f), Shader.TileMode.CLAMP)
        rimSh = LinearGradient(0f, 0f, 1f, 1f,
            if (night) intArrayOf(GlassColor.alpha(white, 0.55f), GlassColor.alpha(white, 0.1f), 0x80000000.toInt()) else intArrayOf(white, 0x1A0B281E, 0x4D0B281E),
            floatArrayOf(0f, if (night) 0.4f else 0.5f, 1f), Shader.TileMode.CLAMP)
        metal = LinearGradient(0f, 0f, 0.7f, 1f,
            if (night) intArrayOf(0xFFB8C4C0.toInt(), 0xFF6E7B77.toInt(), 0xFF2E3735.toInt()) else intArrayOf(white, 0xFFAEB8B4.toInt(), 0xFF5E6966.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        glass = LinearGradient(0f, 0f, 0f, 1f,
            if (night) intArrayOf(0xFFD5E6F2.toInt(), 0xFF7F9BB0.toInt(), 0xFF34495A.toInt()) else intArrayOf(0xFFF4FAFF.toInt(), 0xFF9DB4C4.toInt(), 0xFF56707F.toInt()),
            floatArrayOf(0f, if (night) 0.3f else 0.35f, 1f), Shader.TileMode.CLAMP)
        shadowSh = RadialGradient(0f, 0f, 1f, GlassColor.alpha(t.shadow, t.shadowA), GlassColor.clear(t.shadow), Shader.TileMode.CLAMP)
        fun glow(c: Int) = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(c, 0.8f), GlassColor.alpha(c, 0.3f), GlassColor.clear(c)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        glowAccent = glow(t.glowAccent); glowWarn = glow(t.glowWarn)
    }

    override fun drawBack(c: Canvas) {
        val t = env.theme
        ensureShaders(t)
        bakedParked = parked
        for (i in 0 until 4) if (!live[i]) drawKey(c, t, i, rotor = !parked)
        if (!live[4]) drawWindows(c, t)
        if (!parked) drawChip(c, t)
    }

    override fun drawLive(c: Canvas, nowMs: Long) {
        val t = env.theme
        ensureShaders(t)
        for (i in 0 until 4) if (live[i]) drawKey(c, t, i, rotor = true)
        // the fan's rotor sits on top of its key; when parked it is never baked, so it can turn
        if (parked && !live[1]) drawRotor(c, t)
        if (live[4]) drawWindows(c, t)
        for (i in 0 until 3) if (pending[i]) drawRing(c, t, i, nowMs)
        if (pendingSeg >= 0) drawSegPending(c, t, pendingSeg, nowMs)
    }

    // the segment whose command is on its way: its edge pulses until the car answers
    private fun drawSegPending(c: Canvas, t: GlassTheme, i: Int, now: Long) {
        val a = if (env.allows(GlassEnv.Loop.AMBIENT, stale)) 0.5f + 0.35f * kotlin.math.sin(now / 240.0).toFloat() else 0.7f
        r.set(segX(i), SEG_Y, segX(i) + SEG_W, SEG_Y + SEG_H)
        r.inset(0.75f, 0.75f)
        s.shader = null; s.pathEffect = null; s.strokeWidth = 1.8f
        s.color = GlassColor.alpha(t.glowAccent, a)
        c.drawRoundRect(r, 13.25f, 13.25f, s)
    }

    // a short accent arc runs round the key until the car confirms
    private fun drawRing(c: Canvas, t: GlassTheme, i: Int, now: Long) {
        val cx = keyCx(i)
        val rot = if (env.allows(GlassEnv.Loop.AMBIENT, stale)) ((now * 0.5) % 360.0).toFloat() else 0f
        s.shader = null; s.strokeWidth = 2.4f; s.strokeCap = Paint.Cap.ROUND
        s.color = if (parked) t.glowAccent else GlassColor.alpha(t.glowAccent, 0.42f)
        r.set(cx - RING, CY - RING, cx + RING, CY + RING)
        c.drawArc(r, rot, RING_SWEEP, false, s)
    }

    override fun drawFront(c: Canvas) {}

    private fun drawKey(c: Canvas, t: GlassTheme, i: Int, rotor: Boolean) {
        val x = keyX(i); val cx = x + KS / 2f
        val night = t.night
        val dim = !parked
        if (dim) c.saveLayerAlpha(null, 107)
        val on = when (i) { 0 -> state.locked == true; 1 -> state.climate == true; 2 -> state.boot == true; else -> false }
        val haloA = if (i == 3) 0.8f * flashS.value else if (on && parked) 0.55f else 0f
        if (haloA > 0.004f) c.unitOval(p, if ((i == 2 && on) || i == 3) glowWarn else glowAccent, cx, CY, KS * 0.62f, KS * 0.62f, (haloA * 255f).roundToInt())
        // the key's shadow: a rounded square filled with an elliptical fade, drawn in the unit space of that fade
        c.save(); c.translate(x + 4f + KS / 2f, KY + 8f + KS / 2f); c.scale(KS / 2f, KS / 2f)
        r.set(-1f, -1f, 1f, 1f)
        c.drawRoundRect(r, 22f / (KS / 2f), 22f / (KS / 2f), p.shade(shadowSh))
        c.restore()
        val pressing = pressed == i
        if (pressing) { c.save(); c.scale(0.96f, 0.96f, cx, CY) }
        r.set(x, KY, x + KS, KY + KS)
        bbox(keyBody, r)
        c.drawRoundRect(r, 22f, 22f, p.shade(keyBody))
        r.set(x + 0.6f, KY + 0.6f, x + KS - 0.6f, KY + KS - 0.6f)
        bbox(rimSh, r)
        s.shade(rimSh); s.strokeWidth = 1.2f; s.strokeCap = Paint.Cap.BUTT
        c.drawRoundRect(r, 21.5f, 21.5f, s)
        s.shader = null; s.strokeCap = Paint.Cap.ROUND
        s.color = GlassColor.alpha(0xFFFFFFFF.toInt(), if (night) 0.5f else 1f)
        c.drawLine(x + 18f, KY + 1.5f, x + KS - 18f, KY + 1.5f, s)
        when (i) {
            0 -> drawLock(c, t, cx)
            1 -> drawFan(c, t, cx, rotor)
            2 -> drawBoot(c, t, cx)
            else -> drawLamp(c, t, cx)
        }
        if (pressing) c.restore()
        if (dim) c.restore()
        if (parked) {
            capPaint.color = if (i == 2 && on) t.warn else t.text2
            capPaint.getFontMetrics(fm)
            c.drawText(captions[i], cx, KY + KS + 10f + (18f - (fm.descent - fm.ascent)) / 2f - fm.ascent, capPaint)
        }
    }

    // padlock: a glass shackle and a keyholed body, lit while locked; the shackle lifts and swings open.
    // At rest it is baked in its pose, so its light and shadow fall the same way as the body's.
    private fun drawLock(c: Canvas, t: GlassTheme, cx: Float) {
        val v = lockS.value
        val tone = if (state.locked == true && parked) GlassGlyphs.Tone.LIT else GlassGlyphs.Tone.CHROME
        if (lockS.moving) {
            c.save()
            c.translate(0f, -(1f - v) * 7f)
            c.rotate(-(1f - v) * 28f, cx + 9f, CY - 3f)
            GlassGlyphs.draw(c, shackleSlot.get(K_SHACKLE, t, d, tone) { shackleShape(cx, 1f) })
            c.restore()
        } else {
            val open = v < 0.5f
            GlassGlyphs.draw(c, shackleSlot.get(if (open) K_SHACKLE_OPEN else K_SHACKLE, t, d, tone) { shackleShape(cx, if (open) 0f else 1f) })
        }
        GlassGlyphs.draw(c, lockSlot.get(K_LOCK, t, d, tone) {
            Path().apply {
                addRoundRect(RectF(cx - 15f, CY - 3f, cx + 15f, CY + 19f), 5.5f, 5.5f, Path.Direction.CW)
                op(Path().apply { addCircle(cx, CY + 6f, 3.1f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
                op(Path().apply { addRoundRect(RectF(cx - 1.5f, CY + 6f, cx + 1.5f, CY + 13.5f), 1.2f, 1.2f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            }
        })
    }

    // the shackle's outline in the pose [v] (1 closed, 0 open): lifted and swung on its left leg
    private fun shackleShape(cx: Float, v: Float): Path = GlassGlyphs.outline(shackle, 4.4f).apply {
        if (v < 1f) transform(Matrix().apply { setRotate(-(1f - v) * 28f, cx + 9f, CY - 3f); postTranslate(0f, -(1f - v) * 7f) })
    }

    // climate: a vent fan in a glass housing; its rotor lights and turns while it runs (parked only)
    private fun drawFan(c: Canvas, t: GlassTheme, cx: Float, rotor: Boolean) {
        p.shader = null
        p.color = if (t.night) 0xFF060A09.toInt() else 0xFFE3E9E6.toInt()
        c.drawCircle(cx, CY, 20f, p)
        GlassGlyphs.draw(c, ringSlot.get(K_FANRING, t, d, GlassGlyphs.Tone.CHROME) {
            Path().apply {
                addCircle(cx, CY, 21.6f, Path.Direction.CW)
                op(Path().apply { addCircle(cx, CY, 18.4f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            }
        })
        if (rotor) drawRotor(c, t)
    }

    // The five blades repeat every 72°, so a few frames baked across that turn cover the whole spin with
    // their lighting fixed; the nearest frame is turned by the small remainder, which keeps it smooth.
    private fun drawRotor(c: Canvas, t: GlassTheme) {
        val cx = keyCx(1)
        val tone = if (state.climate == true && parked) GlassGlyphs.Tone.LIT else GlassGlyphs.Tone.CHROME
        val step = 72f / ROTOR_FRAMES
        val turn = ((fanAngle % 72f) + 72f) % 72f
        val f = (turn / step).toInt().coerceIn(0, ROTOR_FRAMES - 1)
        val rest = turn - f * step
        c.save()
        c.rotate(rest, cx, CY)
        GlassGlyphs.draw(c, rotorSlots[f].get(K_ROTOR[f], t, d, tone) { rotorShape(cx, f * step) })
        c.restore()
    }

    private fun rotorShape(cx: Float, angle: Float): Path {
        val shape = Path()
        val m = Matrix()
        for (k in 0 until 5) {
            val bl = Path(blade)
            m.setRotate(angle + k * 72f); m.postTranslate(cx, CY)
            bl.transform(m)
            shape.op(bl, Path.Op.UNION)
        }
        shape.op(Path().apply { addCircle(cx, CY, 3.6f, Path.Direction.CW) }, Path.Op.UNION)
        return shape
    }

    // boot: the rear of an SUV in glass, the opening cut through it, wheels set in their arches; the
    // hatch lifts on its roof hinge (baked in its open pose at rest) and it all lights amber while open
    private fun drawBoot(c: Canvas, t: GlassTheme, cx: Float) {
        val bx = cx - 17f; val by = CY + 9f
        val tone = if (state.boot == true) GlassGlyphs.Tone.WARN else GlassGlyphs.Tone.CHROME
        GlassGlyphs.draw(c, bootSlot.get(K_BOOT, t, d, tone) {
            Path(bootBody).apply {
                op(bootOpening, Path.Op.DIFFERENCE)
                for (wo in BOOT_WHEELS) op(Path().apply { addCircle(bx + wo, by + 1f, 6.8f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
                for (wo in BOOT_WHEELS) op(Path().apply { addCircle(bx + wo, by + 1f, 4.9f, Path.Direction.CW) }, Path.Op.UNION)
            }
        })
        if (bootS.moving) {
            c.save()
            c.rotate(-bootS.value * 62f, bx + 24f, by - 20f)
            GlassGlyphs.draw(c, lidSlot.get(K_LID, t, d, tone) { GlassGlyphs.outline(lid, 3.6f) })
            c.restore()
        } else {
            val open = bootS.value > 0.5f
            GlassGlyphs.draw(c, lidSlot.get(if (open) K_LID_OPEN else K_LID, t, d, tone) {
                GlassGlyphs.outline(lid, 3.6f).apply { if (open) transform(Matrix().apply { setRotate(-62f, bx + 24f, by - 20f) }) }
            })
        }
    }

    // lights: a glass headlamp whose three beams blaze amber when flashed
    private fun drawLamp(c: Canvas, t: GlassTheme, cx: Float) {
        val v = flashS.value.coerceIn(0f, 1f)
        GlassGlyphs.draw(c, lampSlot.get(K_LAMP, t, d, GlassGlyphs.Tone.CHROME) { Path(lamp) })
        if (v > 0.02f) GlassGlyphs.draw(c, lampLitSlot.get(K_LAMP, t, d, GlassGlyphs.Tone.WARN) { Path(lamp) }, (v * 255f).roundToInt())
        val dimBeams = ((1f - v) * 150f).roundToInt()
        if (dimBeams > 3) GlassGlyphs.draw(c, beamsSlot.get(K_BEAMS, t, d, GlassGlyphs.Tone.CHROME) { GlassGlyphs.outline(beams, 3.2f) }, dimBeams)
        if (v > 0.02f) GlassGlyphs.draw(c, beamsLitSlot.get(K_BEAMS, t, d, GlassGlyphs.Tone.WARN) { GlassGlyphs.outline(beams, 3.2f) }, (v * 255f).roundToInt())
    }

    // windows: a side profile whose two panes drop to the chosen position, and a 3-way switch for all four
    private fun drawWindows(c: Canvas, t: GlassTheme) {
        val night = t.night
        r.set(PX, KY, PX + PW, KY + PH)
        bbox(keyBody, r)
        p.shade(keyBody); p.alpha = if (night) 140 else 153
        c.drawRoundRect(r, 20f, 20f, p)
        p.alpha = 255
        r.set(PX + 0.6f, KY + 0.6f, PX + PW - 0.6f, KY + PH - 0.6f)
        bbox(rimSh, r)
        s.shade(rimSh); s.strokeWidth = 1f; s.strokeCap = Paint.Cap.BUTT
        c.drawRoundRect(r, 19.5f, 19.5f, s)
        s.shader = null
        c.unitOval(p, shadowSh, WCX + 4f, wy(-6f), LC * 0.5f, 4f)
        GlassGlyphs.draw(c, carSlot.get(K_CAR, t, d, GlassGlyphs.Tone.CHROME) { Path(carBody) })
        for (f in CAR_WHEELS) {
            val x = wx(f); val y = wy(0f)
            p.shader = null; p.color = if (night) 0xFF0A0F0D.toInt() else 0xFF27322E.toInt()
            c.drawCircle(x, y, 7.5f, p)
            b.set(x - 7.5f, y - 7.5f, x + 7.5f, y + 7.5f)
            bbox(metal, b)
            s.shade(metal); s.strokeWidth = 1.8f
            c.drawCircle(x, y, 7.5f, s)
        }
        s.shader = null
        val drop = dropS.value
        for (w in winPaths) {
            p.shader = null; p.color = if (night) 0xFF010302.toInt() else 0xFF141A18.toInt()
            c.drawPath(w, p)
            c.save()
            c.clipPath(w)
            r.set(wx(-0.42f), wy(W_TOP) + drop * (W_TOP - W_BOT), wx(-0.42f) + LC * 0.66f, wy(W_TOP) + drop * (W_TOP - W_BOT) + (W_TOP - W_BOT + 2f))
            bbox(glass, r)
            c.drawRect(r, p.shade(glass))
            c.restore()
            s.color = GlassColor.alpha(0xFFFFFFFF.toInt(), if (night) 0.35f else 0.8f); s.strokeWidth = 0.9f; s.strokeJoin = Paint.Join.MITER
            c.drawPath(w, s)
        }
        val airA = (drop * 3f).coerceAtMost(1f) * 0.7f
        if (airA > 0.004f) {
            s.color = GlassColor.alpha(t.glowAccent, airA); s.strokeWidth = 1.6f; s.strokeCap = Paint.Cap.ROUND
            c.drawPath(air, s)
        }
        // Close / Vent / Open, dimmed while driving
        val dim = !parked
        if (dim) c.saveLayerAlpha(null, 115)
        for (i in 0 until 3) {
            val sel = state.windows?.ordinal == i
            r.set(segX(i), SEG_Y, segX(i) + SEG_W, SEG_Y + SEG_H)
            p.shader = null
            p.color = if (sel) GlassColor.alpha(t.glowAccent, 0.22f) else if (night) GlassColor.alpha(t.glassBot, 0.35f) else 0x99FFFFFF.toInt()
            c.drawRoundRect(r, 14f, 14f, p)
            r.inset(0.5f, 0.5f)
            s.color = if (sel) t.accent else if (night) 0x29FFFFFF else 0x29183026
            s.strokeWidth = 1f
            c.drawRoundRect(r, 13.5f, 13.5f, s)
            capPaint.color = if (sel) t.text else t.text2
            capPaint.getFontMetrics(fm)
            c.drawText(SEG_LABELS[i], segX(i) + SEG_W / 2f, SEG_Y + SEG_H / 2f - (fm.ascent + fm.descent) / 2f, capPaint)
        }
        if (dim) c.restore()
    }

    private fun drawChip(c: Canvas, t: GlassTheme) {
        capPaint.color = t.text2
        capPaint.getFontMetrics(fm)
        val tw = capPaint.measureText(CHIP)
        val top = KY + KS + 8f
        r.set(310f - tw / 2f - 13f, top, 310f + tw / 2f + 13f, top + 26f)
        r.inset(0.5f, 0.5f)
        s.shader = null; s.color = t.text2; s.strokeWidth = 1f
        c.drawRoundRect(r, 12.5f, 12.5f, s)
        c.drawText(CHIP, 310f, top + 4f + (18f - (fm.descent - fm.ascent)) / 2f - fm.ascent, capPaint)
    }

    /** Stretch a unit shader over [box]: linear ones span it, radial ones fill it as an ellipse. */
    private fun bbox(sh: Shader, box: RectF, radial: Boolean = false) {
        if (radial) { m.setScale(box.width() / 2f, box.height() / 2f); m.postTranslate(box.centerX(), box.centerY()) }
        else { m.setScale(box.width(), box.height()); m.postTranslate(box.left, box.top) }
        sh.setLocalMatrix(m)
    }

    companion object {
        private const val KS = 80f
        private const val KGAP = 16f
        private const val PW = 204f
        private const val PH = 110f
        private const val X0 = 12f
        private const val KY = 37f
        private const val CY = KY + KS / 2f
        private const val PX = X0 + KS * 4f + KGAP * 3f + 24f
        private const val WCX = PX + PW / 2f
        private const val LC = PW * 0.86f
        private const val BASE = KY + 44f
        private const val W_TOP = 31f
        private const val W_BOT = 18f
        private const val SEG_Y = KY + PH - 56f
        private const val SEG_H = 48f
        private const val SEG_W = (PW - 16f - 8f) / 3f
        private const val RING = KS * 0.44f
        /** The mock's 0.6-key dash on a 0.44-key radius ring, in degrees. */
        private const val RING_SWEEP = (KS * 0.6f) / RING * 57.29578f
        private const val PRESS_MS = 140L
        private const val PENDING_MS = 750L
        private const val BLINK_MS = 180L
        private val CHIP get() = gs(R.string.glass_ctl_park_to_use)
        private const val ROTOR_FRAMES = 12
        private const val K_SHACKLE = "ctl.shackle"
        private const val K_SHACKLE_OPEN = "ctl.shackle.open"
        private const val K_LOCK = "ctl.lock"
        private const val K_FANRING = "ctl.fanring"
        private val K_ROTOR = Array(ROTOR_FRAMES) { "ctl.rotor.$it" }
        private const val K_BOOT = "ctl.boot"
        private const val K_LID = "ctl.lid"
        private const val K_LID_OPEN = "ctl.lid.open"
        private const val K_LAMP = "ctl.lamp"
        private const val K_BEAMS = "ctl.beams"
        private const val K_CAR = "ctl.car"
        private val SEG_LABELS get() = arrayOf(gs(R.string.glass_ctl_seg_close), gs(R.string.glass_ctl_seg_vent), gs(R.string.glass_ctl_seg_open))
        private val BOOT_WHEELS = floatArrayOf(8f, 21f)
        private val CAR_WHEELS = floatArrayOf(-0.3f, 0.3f)

        private fun keyX(i: Int) = X0 + i * (KS + KGAP)
        private fun keyCx(i: Int) = keyX(i) + KS / 2f
        private fun segX(i: Int) = PX + 8f + i * (SEG_W + 4f)
        private fun wx(f: Float) = WCX + LC * f
        private fun wy(dy: Float) = BASE - dy

    }
}
