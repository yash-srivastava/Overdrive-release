package com.overdrive.app.overlay.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Saved seat positions — up to four glass pills, each moving the seat to one saved position on a tap,
 * and a "+N" pill for the rest. Seats move only in Park: blocked, the pills dim and the header says why.
 * A moving seat's pill lights with a running ring, then shows a tick, or the error, for a moment.
 */
@SuppressLint("ViewConstructor")
class GlassSeatsView(context: Context, env: GlassEnv, ticker: GlassTicker) : GlassWidgetView(context, env, ticker, 400f, 184f) {

    data class Entry(val id: String, val name: String)

    /** [busyId] is moving now, [doneId] has just arrived, [failText] says why the last move didn't happen. */
    data class State(
        val entries: List<Entry>,
        val blocked: Boolean,
        val blockReason: String?,
        val busyId: String?,
        val doneId: String?,
        val failText: String?,
    )

    /** Moves the seat to the saved position with this id. */
    var onApply: ((String) -> Unit)? = null
    /** Lists every saved position. */
    var onMore: (() -> Unit)? = null

    private var state = State(emptyList(), false, null, null, null, null)
    private var bound = false
    private var shown = 0
    private var more = 0
    private val lines = Array(PILLS) { arrayOf("", "") }
    private var moreText = ""
    private var header = TITLE
    private var failLine = ""
    private val emptyA: String
    private val emptyB: String
    private var rtl = layoutDirection == LAYOUT_DIRECTION_RTL
    // what the baked header and pills show: the daemon's block, or driving
    private var shownBlocked = false

    // feedback targets: 0-3 a pill, MORE the "+N" pill (a position past the pills), -1 none
    private var busyT = -1
    private var doneFor: String? = null
    private var doneT = -1
    private var doneMs = -1L
    private var failFor: String? = null
    private var failT = -1
    private var failMs = -1L
    private var lastTap: String? = null
    private var tapMs = -TAP_GUARD_MS
    // a held pill is drawn live, pressed in; the rest stay baked
    private var pressed = -1
        set(v) { if (field != v) { field = v; layersChanged() } }
    private var down = -1

    private val chromeSlots = Array(PILLS) { GlassGlyphs.Slot() }
    private val litSlots = Array(PILLS) { GlassGlyphs.Slot() }
    private val emptySlot = GlassGlyphs.Slot()
    // the running ring travels round each place's own outline
    private val rings = Array(PLACES) { k ->
        Path().apply {
            val rad = height(k) / 2f + 4f
            addRoundRect(RectF(left(k) - 4f, top(k) - 4f, left(k) + width(k) + 4f, top(k) + height(k) + 4f), rad, rad, Path.Direction.CW)
        }
    }
    private val ringLen = FloatArray(PLACES) { PathMeasure(rings[it], true).length }
    private val measure = PathMeasure()
    private val ringSeg = Path()

    private val head = GlassFonts.paint(context, 500, 15f, 0)
    private val nameP = GlassFonts.paint(context, 600, 15f, 0)
    private val moreP = GlassFonts.paint(context, 700, 14f, 0).apply { textAlign = Paint.Align.CENTER }
    private val emptyP = GlassFonts.paint(context, 600, 16f, 0)
    private val noteP = GlassFonts.paint(context, 500, 14f, 0)
    private val fm = Paint.FontMetrics()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private val dash = DashPathEffect(floatArrayOf(5f, 4f), 0f)

    private val keyShaders = GlassKeyShaders()

    init {
        emptyA = ellipsize(EMPTY_A, EMPTY_W, emptyP)
        emptyB = ellipsize(EMPTY_B, EMPTY_W, noteP)
    }

    private val blocked get() = state.blocked || env.driving

    /** Unchanged data is a no-op. A move that arrives or fails shows its feedback; the first bind shows none. */
    fun bind(s: State, animate: Boolean) {
        if (bound && s == state) return
        val prev = state
        val first = !bound
        bound = true
        state = s
        val listChanged = first || s.entries != prev.entries
        if (listChanged && down >= 0) cancelPress()
        shown = minOf(PILLS, s.entries.size)
        more = s.entries.size - shown
        if (listChanged) for (i in 0 until PILLS) wrap(i)
        moreText = if (more > 0) "+$more" else ""
        val now = SystemClock.uptimeMillis()
        // the same position arriving twice still shows, as the end of a move
        val ended = prev.busyId != null && s.busyId == null
        if (!first && s.doneId != null && (s.doneId != prev.doneId || ended)) { doneFor = s.doneId; doneMs = now }
        val fail = s.failText?.trim()
        if (!first && !fail.isNullOrEmpty() && (s.failText != prev.failText || ended)) {
            failFor = prev.busyId ?: lastTap; failMs = now
            failLine = ellipsize(fail, headW(), head)
        }
        // a new move drops the last one's error
        if (s.busyId != null) { failMs = -1L; failFor = null }
        busyT = target(s.busyId)
        doneT = target(doneFor)
        failT = target(failFor)
        refreshHeader()
        layersChanged()
        requestFrames()
    }

    private fun refreshHeader() {
        shownBlocked = blocked
        header = ellipsize(if (shownBlocked) hint(state.blockReason) else TITLE, headW(), head)
    }

    // the daemon's reason codes read as a short hint; any other text shows as it is
    private fun hint(reason: String?): String {
        val why = reason?.trim().orEmpty()
        return when {
            why == "gear_unknown" || why == "speed_unknown" || why == "state_unknown" -> "Waiting for the car"
            why.isEmpty() || why.all { it in 'a'..'z' || it == '_' } -> "Park to change seats"
            else -> why
        }
    }

    private fun headW() = X1 - X0 - 8f - if (more > 0) MW + 12f else 0f

    /** Where an entry shows: 0-3 its pill, MORE when it's past the pills, -1 not listed. */
    private fun target(id: String?): Int {
        if (id == null) return -1
        val list = state.entries
        for (i in list.indices) if (list[i].id == id) return if (i < PILLS) i else MORE
        return -1
    }

    // the name in up to two lines from the pill's leading edge, the second cut with an ellipsis
    private fun wrap(i: Int) {
        val l = lines[i]
        val e = state.entries.getOrNull(i)
        if (e == null || i >= shown) { l[0] = ""; l[1] = ""; return }
        val words = e.name.trim().ifEmpty { "Seat position" }.split(SPACES)
        var a = ""; var idx = 0
        while (idx < words.size) {
            val t = if (a.isEmpty()) words[idx] else "$a ${words[idx]}"
            if (nameP.measureText(t) > NAME_W && a.isNotEmpty()) break
            a = t; idx++
        }
        l[0] = ellipsize(a, NAME_W, nameP)
        l[1] = ellipsize(words.drop(idx).joinToString(" "), NAME_W, nameP)
    }

    // trims whole code points, so an emoji in a name is never cut in half
    private fun ellipsize(t: String, max: Float, paint: Paint): String {
        if (paint.measureText(t) <= max) return t
        var e = t
        while (e.codePointCount(0, e.length) > 1 && paint.measureText("$e…") > max) e = e.substring(0, e.offsetByCodePoints(e.length, -1))
        return "${e.trimEnd()}…"
    }

    // ── input ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = (e.x - shellLeftPx) / d
        val y = (e.y - shellTopPx) / d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (stale || blocked) return false
                val i = hit(x, y)
                // a pill waits while a seat is moving; the full list can still open
                if (i < 0 || (i < PILLS && state.busyId != null)) return false
                down = i
                pressed = i
                return true
            }
            MotionEvent.ACTION_MOVE -> if (down >= 0 && hit(x, y) != down) { cancelPress(); return true }
            MotionEvent.ACTION_UP -> {
                val i = down
                cancelPress()
                // the car may have left Park while the finger was down
                if (i >= 0 && hit(x, y) == i && !blocked && !stale) { performClick(); tap(i) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { cancelPress(); return true }
        }
        return down >= 0
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun cancelPress() {
        down = -1
        pressed = -1
        invalidate()
    }

    /** 0-3 a pill, MORE the "+N" pill, -1 nothing. */
    private fun hit(x: Float, y: Float): Int {
        for (i in 0 until shown) if (inside(place(i), x, y)) return i
        if (more > 0 && inside(place(MORE), x, y)) return MORE
        return -1
    }

    private fun inside(k: Int, x: Float, y: Float): Boolean {
        val slop = if (k == MORE || k == MORE + 1) 8f else 4f
        return x >= left(k) - slop && x <= left(k) + width(k) + slop && y >= top(k) - slop && y <= top(k) + height(k) + slop
    }

    private fun tap(i: Int) {
        // the overlay may have hidden (reverse, another app) while the finger was down
        if (env.hidden) return
        val now = SystemClock.uptimeMillis()
        // a second tap before the host has answered the first would send the move twice
        if (now - tapMs < TAP_GUARD_MS) return
        tapMs = now
        if (i == MORE) { onMore?.invoke(); return }
        if (state.busyId != null) return
        val e = state.entries.getOrNull(i) ?: return
        lastTap = e.id
        onApply?.invoke(e.id)
    }

    override fun envChanged() {
        if (env.hidden && down >= 0) cancelPress()
        if (blocked != shownBlocked) { refreshHeader(); layersChanged() }
        super.envChanged()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        val v = layoutDirection == LAYOUT_DIRECTION_RTL
        if (v != rtl) { rtl = v; layersChanged() }
    }

    // ── motion ───────────────────────────────────────────────────────────────

    override fun animate(nowMs: Long, dt: Float): Boolean {
        var busy = false
        if (busyT >= 0 && env.allows(GlassEnv.Loop.AMBIENT, stale)) busy = true
        // a position that arrived off the pills shows nothing, so it needs no frames
        if (doneMs >= 0) { if (nowMs - doneMs >= DONE_MS) { doneMs = -1L; doneFor = null; doneT = -1 } else if (doneT >= 0) busy = true }
        // the error's header text replaces the baked one, which comes back when it ends
        if (failMs >= 0) { if (nowMs - failMs < FAIL_MS) busy = true else { failMs = -1L; failFor = null; failT = -1; layersChanged() } }
        return busy
    }

    override fun onThemeChanged() { keyShaders.invalidate() }

    // ── drawing ──────────────────────────────────────────────────────────────

    override fun drawBack(c: Canvas) {
        val t = env.theme
        keyShaders.ensure(t)
        if (failMs < 0) drawHeader(c, header, t.text2, 1f)
        if (shown == 0) { drawEmpty(c, t); return }
        val dim = shownBlocked
        if (dim) c.saveLayerAlpha(0f, 0f, baseW, baseH, DIM_A)
        for (i in 0 until shown) if (pressed != i) drawPill(c, t, i, false)
        if (more > 0 && pressed != MORE) drawMore(c, t, false)
        if (dim) c.restore()
    }

    override fun drawLive(c: Canvas, nowMs: Long) {
        val t = env.theme
        keyShaders.ensure(t)
        val pr = pressed
        if (pr == MORE) { if (more > 0) drawMore(c, t, true) } else if (pr in 0 until shown) drawPill(c, t, pr, true)
        if (busyT >= 0) drawBusy(c, t, busyT, nowMs)
        if (doneMs >= 0 && doneT >= 0) drawResult(c, t, doneT, fade(nowMs - doneMs, DONE_MS), true)
        if (failMs >= 0) {
            val a = fade(nowMs - failMs, FAIL_MS)
            if (failT >= 0) drawResult(c, t, failT, a, false)
            drawHeader(c, failLine, t.warn, a)
        }
    }

    override fun drawFront(c: Canvas) {}

    // full for most of its time, then out
    private fun fade(age: Long, dur: Long): Float {
        val k = age.toFloat() / dur
        if (k >= 1f) return 0f
        if (k <= HOLD) return 1f
        val f = (1f - k) / (1f - HOLD)
        return f * f
    }

    private fun drawHeader(c: Canvas, text: String, color: Int, alpha: Float) {
        if (text.isEmpty() || alpha <= 0.004f) return
        head.color = GlassColor.alpha(color, (color ushr 24) / 255f * alpha)
        head.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        head.getFontMetrics(fm)
        c.drawText(text, if (rtl) baseW - TX else TX, HEAD_CY - (fm.ascent + fm.descent) / 2f, head)
    }

    private fun drawPill(c: Canvas, t: GlassTheme, i: Int, pressing: Boolean) {
        val k = place(i)
        val x = left(k); val y = top(k)
        val cx = x + PW / 2f; val cy = y + PH / 2f
        shadow(c, cx, cy, PW, PH, 3f, 6f)
        if (pressing) { c.save(); c.scale(PRESS_K, PRESS_K, cx, cy) }
        glass(c, t, x, y, PW, PH)
        seat(c, t, chromeSlots[i], glyphCx(k), cy, GlassGlyphs.Tone.CHROME, 255, false)
        nameP.color = t.text
        nameP.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        nameP.getFontMetrics(fm)
        val tx = if (rtl) x + PW - NAME_IN else x + NAME_IN
        val mid = -(fm.ascent + fm.descent) / 2f
        val l = lines[i]
        if (l[1].isEmpty()) c.drawText(l[0], tx, cy + mid, nameP)
        else { c.drawText(l[0], tx, cy - LINE_H / 2f + mid, nameP); c.drawText(l[1], tx, cy + LINE_H / 2f + mid, nameP) }
        if (pressing) c.restore()
    }

    private fun drawMore(c: Canvas, t: GlassTheme, pressing: Boolean) {
        val k = place(MORE)
        val x = left(k)
        val cx = x + MW / 2f; val cy = MY + MH / 2f
        shadow(c, cx, cy, MW, MH, 2f, 4f)
        if (pressing) { c.save(); c.scale(PRESS_K, PRESS_K, cx, cy) }
        glass(c, t, x, MY, MW, MH)
        moreP.color = t.text2
        moreP.getFontMetrics(fm)
        c.drawText(moreText, cx, cy - (fm.ascent + fm.descent) / 2f, moreP)
        if (pressing) c.restore()
    }

    // nothing saved yet: a dashed outline where the pills go, a faint seat, and where to save them
    private fun drawEmpty(c: Canvas, t: GlassTheme) {
        r.set(X0 + 0.7f, R0 + 0.7f, X1 - 0.7f, BODY_B - 0.7f)
        s.shader = null; s.pathEffect = dash; s.strokeWidth = 1.4f; s.color = t.text3
        c.drawRoundRect(r, 26f, 26f, s)
        s.pathEffect = null
        val cy = (R0 + BODY_B) / 2f
        seat(c, t, emptySlot, if (rtl) X1 - EMPTY_GLYPH else X0 + EMPTY_GLYPH, cy, GlassGlyphs.Tone.CHROME, 110, false)
        val tx = if (rtl) X1 - EMPTY_IN else X0 + EMPTY_IN
        val align = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        emptyP.textAlign = align; emptyP.color = t.text2
        emptyP.getFontMetrics(fm)
        c.drawText(emptyA, tx, cy - 11f - (fm.ascent + fm.descent) / 2f, emptyP)
        noteP.textAlign = align; noteP.color = t.text3
        noteP.getFontMetrics(fm)
        c.drawText(emptyB, tx, cy + 12f - (fm.ascent + fm.descent) / 2f, noteP)
    }

    // a pill's shadow: its rounded shape filled with an elliptical fade, drawn in the unit space of that fade
    private fun shadow(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, dx: Float, dy: Float) {
        c.save(); c.translate(cx + dx, cy + dy); c.scale(w / 2f, h / 2f)
        r.set(-1f, -1f, 1f, 1f)
        c.drawRoundRect(r, h / w, 1f, p.shade(keyShaders.shadowSh))
        c.restore()
    }

    // the glass body, its graded rim, and the lit line along its top
    private fun glass(c: Canvas, t: GlassTheme, x: Float, y: Float, w: Float, h: Float) {
        val rad = h / 2f
        r.set(x, y, x + w, y + h)
        keyShaders.mapToBounds(keyShaders.keyBody, r)
        c.drawRoundRect(r, rad, rad, p.shade(keyShaders.keyBody))
        r.set(x + 0.6f, y + 0.6f, x + w - 0.6f, y + h - 0.6f)
        keyShaders.mapToBounds(keyShaders.rimSh, r)
        s.shade(keyShaders.rimSh); s.pathEffect = null; s.strokeWidth = 1.2f; s.strokeCap = Paint.Cap.BUTT
        c.drawRoundRect(r, rad - 0.6f, rad - 0.6f, s)
        s.shader = null; s.strokeCap = Paint.Cap.ROUND
        s.color = GlassColor.alpha(0xFFFFFFFF.toInt(), if (t.night) 0.5f else 1f); s.strokeWidth = 1.2f
        c.drawLine(x + rad, y + 1.5f, x + w - rad, y + 1.5f, s)
    }

    // the seat glyph in its 32-unit box; it faces the way the layout reads and is lit from the top left either way
    private fun seat(c: Canvas, t: GlassTheme, slot: GlassGlyphs.Slot, cx: Float, cy: Float, tone: GlassGlyphs.Tone, alpha: Int, bright: Boolean) {
        val mirror = rtl
        c.save()
        c.translate(cx - 16f * GLYPH, cy - 16f * GLYPH)
        c.scale(GLYPH, GLYPH)
        GlassGlyphs.draw(c, slot.get(if (mirror) K_SEAT_RTL else K_SEAT, t, d * GLYPH, tone, 0, bright) { seatShape(mirror) }, alpha)
        c.restore()
    }

    // a short accent arc runs round the place while its seat moves, and a pill's seat lights and breathes
    private fun drawBusy(c: Canvas, t: GlassTheme, i: Int, now: Long) {
        val moving = env.allows(GlassEnv.Loop.AMBIENT, stale)
        val k = place(i)
        if (i < PILLS) {
            val breath = if (moving) 0.6f + 0.4f * sin(now / 260.0).toFloat() else 1f
            seat(c, t, litSlots[i], glyphCx(k), top(k) + PH / 2f, GlassGlyphs.Tone.LIT, (breath * 255f).roundToInt(), true)
        }
        val len = ringLen[k]
        val seg = len * 0.28f
        val start = if (moving) (now % LOOP_MS) / LOOP_MS.toFloat() * len else 0f
        measure.setPath(rings[k], true)
        ringSeg.rewind()
        measure.getSegment(start, minOf(len, start + seg), ringSeg, true)
        if (start + seg > len) measure.getSegment(0f, start + seg - len, ringSeg, true)
        s.shader = null; s.pathEffect = null; s.strokeWidth = 2.4f; s.strokeCap = Paint.Cap.ROUND; s.strokeJoin = Paint.Join.ROUND
        s.color = t.glowAccent
        c.drawPath(ringSeg, s)
    }

    // arrived: an accent bloom and a tick; failed: an amber bloom and a bang; both hold, then fade
    private fun drawResult(c: Canvas, t: GlassTheme, i: Int, a: Float, ok: Boolean) {
        if (a <= 0.004f) return
        val k = place(i)
        val x = left(k); val y = top(k); val w = width(k); val h = height(k)
        val cx = x + w / 2f; val cy = y + h / 2f
        c.unitOval(p, if (ok) keyShaders.glowAccent else keyShaders.glowWarn, cx, cy, w * 0.58f, h * 0.85f, (a * 190f).roundToInt())
        if (ok && i < PILLS) seat(c, t, litSlots[i], glyphCx(k), cy, GlassGlyphs.Tone.LIT, (a * 255f).roundToInt(), true)
        // the badge sits on the trailing top corner
        val inset = if (i < PILLS) 16f else 6f
        val bx = if (rtl) x + inset else x + w - inset; val by = y + 4f
        p.shader = null; p.color = GlassColor.alpha(if (ok) t.glowAccent else t.glowWarn, a)
        c.drawCircle(bx, by, 10f, p)
        s.shader = null; s.pathEffect = null; s.color = GlassColor.alpha(if (t.night) 0xFF03140F.toInt() else 0xFFFFFFFF.toInt(), a)
        s.strokeWidth = 2.2f; s.strokeCap = Paint.Cap.ROUND; s.strokeJoin = Paint.Join.ROUND
        if (ok) { c.drawLine(bx - 4f, by, bx - 1f, by + 3.2f, s); c.drawLine(bx - 1f, by + 3.2f, bx + 4.5f, by - 3.5f, s) }
        else { c.drawLine(bx, by - 4.5f, bx, by + 1f, s); c.drawPoint(bx, by + 4.4f, s) }
    }

    // pill i's place, in reading order (right to left in an RTL layout); one or two pills sit in a centred row
    private fun place(i: Int): Int {
        if (i == MORE) return if (rtl) MORE + 1 else MORE
        val col = if (rtl) i xor 1 else i
        return if (shown <= 2) ROW + col else col
    }

    private fun glyphCx(k: Int) = if (rtl) left(k) + PW - GLYPH_IN else left(k) + GLYPH_IN

    /** The middle of the "+N" pill, as a fraction of the widget's width, to anchor the full list. */
    fun moreCenterFraction() = (left(place(MORE)) + MW / 2f) / baseW

    companion object {
        private const val PILLS = 4
        private const val MORE = 4
        private const val ROW = 6
        // places: 0-3 the grid, 4 and 5 the "+N" pill at the right or left end, 6 and 7 a centred row
        private const val PLACES = 8
        private const val X0 = 18f
        private const val X1 = 382f
        private const val GAP = 12f
        private const val PW = (X1 - X0 - GAP) / 2f
        private const val PH = 54f
        private const val R0 = 46f
        private const val BODY_B = R0 + PH * 2f + GAP
        private const val MW = 58f
        private const val MH = 28f
        private const val MY = 9f
        private const val TX = 22f
        private const val HEAD_CY = MY + MH / 2f
        private const val GLYPH = 1.2f
        private const val GLYPH_IN = 30f
        private const val NAME_IN = 54f
        private const val NAME_W = PW - NAME_IN - 16f
        private const val LINE_H = 18f
        private const val EMPTY_GLYPH = 46f
        private const val EMPTY_IN = 86f
        private const val EMPTY_W = X1 - X0 - EMPTY_IN - 18f
        private const val PRESS_K = 0.96f
        private const val DIM_A = 107
        private const val HOLD = 0.65f
        private const val DONE_MS = 2000L
        private const val FAIL_MS = 3000L
        private const val LOOP_MS = 1100L
        private const val TAP_GUARD_MS = 600L
        private const val TITLE = "Seat positions"
        private const val EMPTY_A = "No saved seat positions yet"
        private const val EMPTY_B = "Save them on the Seat Positions page"
        private const val K_SEAT = "seats.seat"
        private const val K_SEAT_RTL = "seats.seat.rtl"
        private val SPACES = Regex("\\s+")

        private fun left(k: Int) = when (k) {
            MORE -> X1 - MW
            MORE + 1 -> X0
            else -> X0 + (k % 2) * (PW + GAP)
        }

        private fun top(k: Int) = when (k) {
            MORE, MORE + 1 -> MY
            0, 1 -> R0
            2, 3 -> R0 + PH + GAP
            else -> R0 + (PH + GAP) / 2f
        }

        private fun width(k: Int) = if (k == MORE || k == MORE + 1) MW else PW
        private fun height(k: Int) = if (k == MORE || k == MORE + 1) MH else PH

        // a car seat side-on, facing right: a reclined, tapering back with its headrest, the cushion, the rail
        private fun seatShape(mirror: Boolean): Path {
            val back = Path(GlassPaths.svg("M7.6 21 L8.6 11.1 C8.8 9.4 10 8.2 11.5 8.2 C13 8.2 14.2 9.4 14.4 11.1 L15.4 21 C15.5 22.5 14.6 23.6 13.2 23.6 H9.8 C8.4 23.6 7.5 22.5 7.6 21 Z"))
            back.op(Path().apply { addRoundRect(RectF(9f, 2.6f, 14f, 6.6f), 2f, 2f, Path.Direction.CW) }, Path.Op.UNION)
            back.op(Path().apply { addRect(10.3f, 6.2f, 12.7f, 8.8f, Path.Direction.CW) }, Path.Op.UNION)
            back.transform(Matrix().apply { setRotate(-13f, 11.5f, 23.6f) })
            val shape = Path().apply { addRoundRect(RectF(10.6f, 20.6f, 27f, 26.2f), 2.8f, 2.8f, Path.Direction.CW) }
            shape.op(back, Path.Op.UNION)
            shape.op(Path().apply { addRoundRect(RectF(13.2f, 27.6f, 24.8f, 29.4f), 0.9f, 0.9f, Path.Direction.CW) }, Path.Op.UNION)
            if (mirror) shape.transform(Matrix().apply { setScale(-1f, 1f, 16f, 16f) })
            return shape
        }
    }
}
