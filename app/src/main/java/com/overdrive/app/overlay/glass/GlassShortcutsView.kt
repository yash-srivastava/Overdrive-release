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
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import kotlin.math.roundToInt

/**
 * Automation shortcuts — three glass keys, each running one automation on a tap. Holding a key (or
 * tapping an empty one) asks to choose its automation or clear it; that only works parked. A key
 * shows what it does as a glass glyph, lit with a running ring while the request is out, and a short glow after.
 */
@SuppressLint("ViewConstructor")
class GlassShortcutsView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, 420f, 184f) {

    /** One key: [id] null is an empty slot; [missing] means its automation was deleted; [off] that it's turned off. */
    data class Slot(val id: String?, val name: String, val glyph: String, val missing: Boolean = false, val off: Boolean = false)

    /** Runs a slot's automation; the callback gets true (started), false (failed) or null (it no longer exists). */
    var onRun: ((Int, String, (Boolean?) -> Unit) -> Unit)? = null
    /** Opens the chooser for a slot. */
    var onEdit: ((Int) -> Unit)? = null

    private var slots = List(SLOTS) { Slot(null, "", "plus") }
    private var bound = false
    // the automation a key's request is out for; a reply for anything else is stale and dropped
    private val runningId = arrayOfNulls<String>(SLOTS)
    private val resultAt = LongArray(SLOTS) { -1L }
    private val resultOk = BooleanArray(SLOTS)
    private val baseSlots = Array(SLOTS) { GlassGlyphs.Slot() }
    private val litSlots = Array(SLOTS) { GlassGlyphs.Slot() }
    // the running ring travels round each key's own rounded outline
    private val rings = Array(SLOTS) { i ->
        Path().apply { addRoundRect(RectF(keyX(i) - 5f, KY - 5f, keyX(i) + KW + 5f, KY + KH + 5f), 26f, 26f, Path.Direction.CW) }
    }
    private val ringLen = FloatArray(SLOTS) { PathMeasure(rings[it], true).length }
    private val measure = PathMeasure()
    private val ringSeg = Path()
    // a held key is drawn live, pressed in; the rest stay baked
    private var pressed = -1
        set(v) { if (field != v) { field = v; layersChanged() } }
    private var down = -1
    private var longFired = false
    private var hintSlot = -1
    private var hintText = ""
    private var hintUntil = 0L
    private val captions = Array(SLOTS) { arrayOf("", "") }

    private val cap = GlassFonts.paint(context, 600, 14f, 0).apply { textAlign = Paint.Align.CENTER }
    private val badge = GlassFonts.paint(context, 700, 12f, 0).apply { textAlign = Paint.Align.CENTER }
    private val fm = Paint.FontMetrics()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()
    private val r = RectF()
    private val dash = DashPathEffect(floatArrayOf(5f, 4f), 0f)

    private var shaderNight: Boolean? = null
    private lateinit var keyBody: LinearGradient
    private lateinit var rimSh: LinearGradient
    private lateinit var shadowSh: RadialGradient
    private lateinit var glowAccent: RadialGradient
    private lateinit var glowWarn: RadialGradient

    private val parked get() = !env.driving
    private val longPress = Runnable {
        val i = down
        if (i < 0) return@Runnable
        longFired = true
        pressed = -1
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        edit(i)
        invalidate()
    }

    /** Unchanged data is a no-op. A key given another automation drops what it was showing for the old one. */
    fun bind(next: List<Slot>, animate: Boolean) {
        if (bound && next == slots) { invalidate(); return }
        bound = true
        val prev = slots
        slots = List(SLOTS) { next.getOrNull(it) ?: Slot(null, "", "plus") }
        for (i in 0 until SLOTS) {
            if (prev[i].id != slots[i].id) {
                runningId[i] = null; resultAt[i] = -1L
                if (hintSlot == i) hintSlot = -1
            }
            wrap(i)
        }
        layersChanged()
        requestFrames()
    }

    // the name in up to two centred lines, the second cut with an ellipsis
    private fun wrap(i: Int) {
        val sl = slots[i]
        val lines = captions[i]
        if (sl.id == null) { lines[0] = gs(R.string.glass_sc_add); lines[1] = ""; return }
        val max = SW - 12f
        // a removed or turned-off key keeps its name on one line, with what's wrong under it
        if (sl.missing || sl.off) {
            lines[0] = ellipsize(sl.name.ifBlank { gs(R.string.glass_automation) }, max)
            lines[1] = if (sl.missing) gs(R.string.glass_sc_removed) else gs(R.string.glass_sc_turned_off)
            return
        }
        val words = sl.name.ifBlank { gs(R.string.glass_automation) }.split(' ').filter { it.isNotEmpty() }
        var a = ""; var idx = 0
        while (idx < words.size) {
            val t = if (a.isEmpty()) words[idx] else "$a ${words[idx]}"
            if (cap.measureText(t) > max && a.isNotEmpty()) break
            a = t; idx++
        }
        var b = words.drop(idx).joinToString(" ")
        if (cap.measureText(a) > max) a = ellipsize(a, max)
        if (b.isNotEmpty() && cap.measureText(b) > max) b = ellipsize(b, max)
        lines[0] = a; lines[1] = b
    }

    // trims whole code points, so an emoji in a name is never cut in half
    private fun ellipsize(t: String, max: Float): String {
        if (cap.measureText(t) <= max) return t
        var e = t
        while (e.codePointCount(0, e.length) > 1 && cap.measureText("$e…") > max) e = e.substring(0, e.offsetByCodePoints(e.length, -1))
        return "$e…"
    }

    // ── input ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = (e.x - shellLeftPx) / d
        val y = (e.y - shellTopPx) / d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (stale) return false
                down = hit(x, y)
                if (down < 0) return false
                // holding a key edits it, so the widget's own hold-to-arrange stays out of the way
                parent?.requestDisallowInterceptTouchEvent(true)
                longFired = false
                pressed = down
                postDelayed(longPress, LONG_MS)
                requestFrames(); invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (down >= 0 && hit(x, y) != down) { cancelPress(); return true }
            MotionEvent.ACTION_UP -> {
                val i = down
                val fired = longFired
                cancelPress()
                if (i >= 0 && !fired && hit(x, y) == i) { performClick(); tap(i) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { cancelPress(); return true }
        }
        return down >= 0
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun cancelPress() {
        removeCallbacks(longPress)
        down = -1
        pressed = -1
        invalidate()
    }

    private fun hit(x: Float, y: Float): Int {
        if (y < KY - 4f || y > KY + KH + 34f) return -1
        for (i in 0 until SLOTS) {
            val l = X0 + i * SW
            if (x >= l && x <= l + SW) return i
        }
        return -1
    }

    private fun tap(i: Int) {
        val sl = slots[i]
        val id = sl.id
        if (id == null) { edit(i); return }
        if (sl.missing) { hint(i, gs(R.string.glass_sc_hold_to_change)); return }
        if (sl.off) { hint(i, gs(R.string.glass_sc_off_in_automations)); return }
        if (runningId[i] != null) return
        // a tap during the last run's tick or bang would queue that automation a second time
        if (resultAt[i] >= 0 && SystemClock.uptimeMillis() - resultAt[i] < RESULT_MS) return
        val run = onRun ?: return
        runningId[i] = id
        resultAt[i] = -1L
        requestFrames(); invalidate()
        run(i, id) { ok ->
            // the key may have been given another automation while this one was running
            if (runningId[i] != id) return@run
            runningId[i] = null
            if (ok == null) {
                bind(slots.mapIndexed { k, v -> if (k == i && v.id == id) v.copy(missing = true) else v }, true)
            } else {
                resultOk[i] = ok
                resultAt[i] = SystemClock.uptimeMillis()
            }
            requestFrames(); invalidate()
        }
    }

    private fun edit(i: Int) {
        // the overlay may have hidden (reverse, another app) while the finger was down
        if (env.hidden) return
        if (!parked) { hint(i, gs(R.string.glass_sc_park_to_edit)); return }
        onEdit?.invoke(i)
    }

    override fun envChanged() {
        if (env.hidden && down >= 0) cancelPress()
        super.envChanged()
    }

    private fun hint(i: Int, text: String) {
        hintSlot = i; hintText = text; hintUntil = SystemClock.uptimeMillis() + HINT_MS
        requestFrames(); invalidate()
    }

    // ── motion ───────────────────────────────────────────────────────────────

    override fun animate(nowMs: Long, dt: Float): Boolean {
        var busy = false
        for (i in 0 until SLOTS) {
            if (runningId[i] != null && env.allows(GlassEnv.Loop.AMBIENT, stale)) busy = true
            if (resultAt[i] >= 0) { if (nowMs - resultAt[i] < RESULT_MS) busy = true else resultAt[i] = -1L }
        }
        if (hintSlot >= 0) { if (nowMs < hintUntil) busy = true else { hintSlot = -1; busy = true } }
        if (pressed >= 0) busy = true
        return busy
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
        shadowSh = RadialGradient(0f, 0f, 1f, GlassColor.alpha(t.shadow, t.shadowA), GlassColor.clear(t.shadow), Shader.TileMode.CLAMP)
        fun glow(c: Int) = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(c, 0.8f), GlassColor.alpha(c, 0.3f), GlassColor.clear(c)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        glowAccent = glow(t.glowAccent); glowWarn = glow(t.glowWarn)
    }

    override fun drawBack(c: Canvas) {
        val t = env.theme
        ensureShaders(t)
        for (i in 0 until SLOTS) if (pressed != i) drawKey(c, t, i, false)
    }

    override fun drawLive(c: Canvas, nowMs: Long) {
        val t = env.theme
        ensureShaders(t)
        if (pressed >= 0) drawKey(c, t, pressed, true)
        for (i in 0 until SLOTS) {
            if (runningId[i] != null) drawRing(c, t, i, nowMs)
            if (resultAt[i] >= 0) drawResult(c, t, i, nowMs)
        }
        if (hintSlot >= 0 && nowMs < hintUntil) drawHint(c, t, nowMs)
    }

    override fun drawFront(c: Canvas) {}

    private fun keyX(i: Int) = X0 + i * SW + (SW - KW) / 2f

    private fun drawKey(c: Canvas, t: GlassTheme, i: Int, pressing: Boolean) {
        val sl = slots[i]
        val x = keyX(i); val cx = x + KW / 2f; val cy = KY + KH / 2f
        val empty = sl.id == null
        val dim = sl.missing || sl.off
        // the key's shadow, then its glass, drawn pressed-in when held
        c.save(); c.translate(cx + 4f, KY + 8f + KH / 2f); c.scale(KW / 2f, KH / 2f)
        r.set(-1f, -1f, 1f, 1f)
        if (!empty) c.drawRoundRect(r, 22f / (KW / 2f), 22f / (KH / 2f), p.shade(shadowSh))
        c.restore()
        if (pressing) { c.save(); c.scale(0.95f, 0.95f, cx, cy) }
        r.set(x, KY, x + KW, KY + KH)
        bbox(keyBody, r)
        p.shade(keyBody)
        if (empty) p.alpha = 110
        c.drawRoundRect(r, 22f, 22f, p)
        p.alpha = 255
        r.set(x + 0.6f, KY + 0.6f, x + KW - 0.6f, KY + KH - 0.6f)
        if (empty) {
            s.shader = null; s.pathEffect = dash; s.strokeWidth = 1.4f; s.color = t.text3
            c.drawRoundRect(r, 21.5f, 21.5f, s)
            s.pathEffect = null
        } else {
            bbox(rimSh, r)
            s.shade(rimSh); s.strokeWidth = 1.2f; s.strokeCap = Paint.Cap.BUTT
            c.drawRoundRect(r, 21.5f, 21.5f, s)
            s.shader = null; s.strokeCap = Paint.Cap.ROUND
            s.color = GlassColor.alpha(0xFFFFFFFF.toInt(), if (t.night) 0.5f else 1f); s.strokeWidth = 1.2f
            c.drawLine(x + 20f, KY + 1.5f, x + KW - 20f, KY + 1.5f, s)
        }
        drawGlyph(c, t, i, sl.glyph, cx, cy, empty, dim)
        if (pressing) c.restore()
        // two caption lines under the key
        cap.getFontMetrics(fm)
        val l0 = KY + KH + 10f - fm.ascent
        cap.color = if (empty || dim) t.text3 else t.text2
        c.drawText(captions[i][0], cx, l0, cap)
        if (captions[i][1].isNotEmpty()) {
            cap.color = if (sl.missing) t.warn else t.text3
            c.drawText(captions[i][1], cx, l0 + 17f, cap)
        }
    }

    // the glyph is a small glass object in a 32-unit box: tinted glass, or faded metal when its automation is off or gone
    private fun drawGlyph(c: Canvas, t: GlassTheme, i: Int, glyph: String, cx: Float, cy: Float, empty: Boolean, dim: Boolean) {
        c.save()
        c.translate(cx - 16f * GLYPH, cy - 16f * GLYPH)
        c.scale(GLYPH, GLYPH)
        if (empty) {
            s.shader = null; s.pathEffect = null; s.color = t.text3; s.strokeWidth = 2.6f; s.strokeCap = Paint.Cap.ROUND
            c.drawPath(GlassIcons.plus, s)
        } else {
            val tone = if (dim) GlassGlyphs.Tone.CHROME else GlassGlyphs.Tone.TINT
            GlassGlyphs.draw(c, baseSlots[i].get(glyph, t, d * GLYPH, tone, if (dim) 0 else GlassIcons.hue(glyph)) { GlassIcons.outline(glyph) }, if (dim) 110 else 255)
        }
        c.restore()
    }

    // over the baked key: only the glyph lit from inside, at the key's pressed scale when it's held
    private fun drawLit(c: Canvas, t: GlassTheme, i: Int, alpha: Int) {
        val glyph = slots[i].glyph
        val cx = keyX(i) + KW / 2f; val cy = KY + KH / 2f
        c.save()
        if (pressed == i) c.scale(0.95f, 0.95f, cx, cy)
        c.translate(cx - 16f * GLYPH, cy - 16f * GLYPH)
        c.scale(GLYPH, GLYPH)
        GlassGlyphs.draw(c, litSlots[i].get(glyph, t, d * GLYPH, GlassGlyphs.Tone.TINT, GlassIcons.hue(glyph), bright = true) { GlassIcons.outline(glyph) }, alpha)
        c.restore()
    }

    // a short accent arc runs round the key's outline while the request is out, and the glyph lights
    private fun drawRing(c: Canvas, t: GlassTheme, i: Int, now: Long) {
        val moving = env.allows(GlassEnv.Loop.AMBIENT, stale)
        val breath = if (moving) 0.6f + 0.4f * kotlin.math.sin(now / 260.0).toFloat() else 1f
        drawLit(c, t, i, (breath * 255f).roundToInt())
        val len = ringLen[i]
        val seg = len * 0.28f
        val start = if (moving) ((now * 0.42) % len).toFloat() else 0f
        measure.setPath(rings[i], true)
        ringSeg.rewind()
        measure.getSegment(start, minOf(len, start + seg), ringSeg, true)
        if (start + seg > len) measure.getSegment(0f, start + seg - len, ringSeg, true)
        s.shader = null; s.pathEffect = null; s.strokeWidth = 2.4f; s.strokeCap = Paint.Cap.ROUND; s.strokeJoin = Paint.Join.ROUND
        s.color = t.glowAccent
        c.drawPath(ringSeg, s)
    }

    // started: a soft accent bloom and a tick; failed: an amber bloom and a bang; both fade out
    private fun drawResult(c: Canvas, t: GlassTheme, i: Int, now: Long) {
        val k = ((now - resultAt[i]).toFloat() / RESULT_MS).coerceIn(0f, 1f)
        val a = (1f - k) * (1f - k)
        val cx = keyX(i) + KW / 2f; val cy = KY + KH / 2f
        val ok = resultOk[i]
        c.unitOval(p, if (ok) glowAccent else glowWarn, cx, cy, KW * 0.66f, KH * 0.7f, (a * 200f).roundToInt())
        if (ok) drawLit(c, t, i, (a * 255f).roundToInt())
        val bx = keyX(i) + KW - 6f; val by = KY + 6f
        p.shader = null; p.color = GlassColor.alpha(if (ok) t.glowAccent else t.glowWarn, a)
        c.drawCircle(bx, by, 10f, p)
        s.shader = null; s.pathEffect = null; s.color = GlassColor.alpha(if (t.night) 0xFF03140F.toInt() else 0xFFFFFFFF.toInt(), a)
        s.strokeWidth = 2.2f; s.strokeCap = Paint.Cap.ROUND; s.strokeJoin = Paint.Join.ROUND
        if (ok) { c.drawLine(bx - 4f, by, bx - 1f, by + 3.2f, s); c.drawLine(bx - 1f, by + 3.2f, bx + 4.5f, by - 3.5f, s) }
        else { c.drawLine(bx, by - 4.5f, bx, by + 1f, s); c.drawPoint(bx, by + 4.4f, s) }
    }

    // a one-line note over the key's caption
    private fun drawHint(c: Canvas, t: GlassTheme, now: Long) {
        val i = hintSlot
        val cx = keyX(i) + KW / 2f
        val fade = ((hintUntil - now).toFloat() / 300f).coerceIn(0f, 1f)
        badge.getFontMetrics(fm)
        val w = badge.measureText(hintText) + 18f
        r.set(cx - w / 2f, KY + KH + 8f, cx + w / 2f, KY + KH + 32f)
        p.shader = null; p.color = GlassColor.alpha(if (t.night) t.glassBot else 0xFFFFFFFF.toInt(), 0.92f * fade)
        c.drawRoundRect(r, 12f, 12f, p)
        s.shader = null; s.pathEffect = null; s.strokeWidth = 1f; s.color = GlassColor.alpha(t.warn, fade)
        c.drawRoundRect(r, 12f, 12f, s)
        badge.color = GlassColor.alpha(t.warn, fade)
        c.drawText(hintText, cx, r.centerY() - (fm.ascent + fm.descent) / 2f, badge)
    }

    private fun bbox(sh: Shader, box: RectF) {
        m.setScale(box.width(), box.height()); m.postTranslate(box.left, box.top)
        sh.setLocalMatrix(m)
    }

    /** The middle of slot [i]'s key, as a fraction of the widget's width. */
    fun slotCenterFraction(i: Int) = (keyX(i) + KW / 2f) / baseW

    companion object {
        const val SLOTS = 3
        private const val X0 = 18f
        private const val SW = 128f
        private const val KW = 100f
        private const val KH = 78f
        private const val KY = 26f
        private const val GLYPH = 1.8f
        private const val LONG_MS = 480L
        private const val RESULT_MS = 1400L
        private const val HINT_MS = 1600L
    }
}
