package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The chooser behind a shortcut key: a glass sheet listing the automations (the current one ticked),
 * scrolled by finger with a fling, and a fixed row at the foot that clears the key. [items] null is
 * still loading, or [failed] to load. Every pick, the clear row and a touch outside close it.
 */
@SuppressLint("ViewConstructor")
class GlassPickerSheet(
    context: Context,
    env: GlassEnv,
    ticker: GlassTicker,
    private val title: String,
    private val items: List<Item>?,
    private val failed: Boolean,
    private val selected: String?,
    private val onPick: (Item) -> Unit,
    private val onRemove: () -> Unit,
) : GlassWidgetView(context, env, ticker, W, heightFor(items, selected != null)) {

    data class Item(val id: String, val name: String, val mode: String, val glyph: String)

    private val canRemove = selected != null
    private val listTop = HEAD
    private val listBottom = baseH - (if (canRemove) ROW + FOOT_GAP else 10f)
    private val content = (items?.size ?: 0) * ROW
    private val maxScroll = max(0f, content - (listBottom - listTop))
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var tracker: VelocityTracker? = null
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    // a touch that stops a fling only stops it; it doesn't pick the row under the finger
    private var stoppedFling = false
    // the finger that scrolls; a second finger never picks, and the first lifting hands over to it
    private var activeId = -1
    private var pressedRow = -2
    private val rowSlots = Array(items?.size ?: 0) { GlassGlyphs.Slot() }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val titleP = GlassFonts.paint(context, 600, 19f, 0)
    private val sub = GlassFonts.paint(context, 500, 14f, 0)
    private val name = GlassFonts.paint(context, 600, 16f, 0)
    private val badge = GlassFonts.paint(context, 600, 12f, 0).apply { textAlign = Paint.Align.RIGHT }
    private val note = GlassFonts.paint(context, 500, 15f, 0).apply { textAlign = Paint.Align.CENTER }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fm = Paint.FontMetrics()
    private val r = RectF()
    private val names = items?.map { it.name } ?: emptyList()
    private val shownNames = arrayOfNulls<String>(names.size)
    private var fadeNight: Boolean? = null
    private lateinit var fadeTop: LinearGradient
    private lateinit var fadeBottom: LinearGradient

    init {
        // open with the current automation in view
        val at = items?.indexOfFirst { it.id == selected } ?: -1
        if (at >= 0) scroll = (at * ROW - (listBottom - listTop) / 2f + ROW / 2f).coerceIn(0f, maxScroll)
    }

    // ── input ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val y = (e.y - shellTopPx) / d
        val x = (e.x - shellLeftPx) / d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stoppedFling = !scroller.isFinished
                scroller.forceFinished(true)
                tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                activeId = e.getPointerId(0)
                downY = e.y; lastY = e.y; dragging = false
                pressedRow = rowAt(x, y)
                layersChanged()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { tracker?.addMovement(e); if (pressedRow != -2) { pressedRow = -2; layersChanged() } }
            MotionEvent.ACTION_POINTER_UP -> {
                tracker?.addMovement(e)
                val i = e.actionIndex
                if (e.getPointerId(i) == activeId) {
                    val n = if (i == 0) 1 else 0
                    activeId = e.getPointerId(n); lastY = e.getY(n)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val i = e.findPointerIndex(activeId)
                if (i < 0) return true
                val ey = e.getY(i)
                if (!dragging && abs(ey - downY) > slop && maxScroll > 0f) { dragging = true; pressedRow = -2; layersChanged() }
                if (dragging) {
                    scroll = (scroll - (ey - lastY) / d).coerceIn(0f, maxScroll)
                    invalidate()
                }
                lastY = ey
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(e)
                if (dragging) {
                    // the fling runs in device px so it moves smoothly, not in whole design units
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.getYVelocity(e.getPointerId(0)) ?: 0f
                    scroller.fling(0, (scroll * d).roundToInt(), 0, -vy.roundToInt(), 0, 0, 0, (maxScroll * d).roundToInt())
                    requestFrames()
                } else if (!stoppedFling) {
                    val row = rowAt(x, y)
                    if (row == pressedRow) pick(row)
                }
                pressedRow = -2
                layersChanged()
                tracker?.recycle(); tracker = null
            }
            MotionEvent.ACTION_CANCEL -> { pressedRow = -2; layersChanged(); tracker?.recycle(); tracker = null }
        }
        return true
    }

    /** -1 the clear row, 0.. a list row, -2 nothing. */
    private fun rowAt(x: Float, y: Float): Int {
        if (x < 10f || x > W - 10f) return -2
        if (canRemove && y >= baseH - ROW - 6f && y <= baseH - 6f) return -1
        val list = items ?: return -2
        if (y < listTop || y > listBottom) return -2
        val i = ((y - listTop + scroll) / ROW).toInt()
        return if (i in list.indices) i else -2
    }

    private fun pick(row: Int) {
        if (row == -1) { onRemove(); return }
        val list = items ?: return
        if (row in list.indices) onPick(list[row])
    }

    override fun animate(nowMs: Long, dt: Float): Boolean {
        if (!scroller.computeScrollOffset()) return false
        scroll = (scroller.currY / d).coerceIn(0f, maxScroll)
        return true
    }

    /** Whether a point in this view (px) is on the sheet's glass rather than its shadow margin. */
    fun onGlass(px: Float, py: Float) = px >= shellLeftPx && px <= shellLeftPx + baseW * d && py >= shellTopPx && py <= shellTopPx + baseH * d

    // ── drawing ──────────────────────────────────────────────────────────────

    override fun drawBack(c: Canvas) {
        val t = env.theme
        titleP.color = t.text
        c.drawText(title, 24f, 34f, titleP)
        sub.color = t.text2
        c.drawText(when { failed -> gs(R.string.glass_picker_failed); items == null -> gs(R.string.glass_picker_loading); else -> gs(R.string.glass_picker_hint) }, 24f, 54f, sub)
        s.color = t.etch; s.strokeWidth = 1f
        c.drawLine(16f, HEAD - 1f, W - 16f, HEAD - 1f, s)
        if (canRemove) {
            val top = baseH - ROW - 6f
            r.set(12f, top, W - 12f, top + ROW)
            p.shader = null
            p.color = GlassColor.alpha(t.glowWarn, if (pressedRow == -1) 0.22f else 0.1f)
            c.drawRoundRect(r, 16f, 16f, p)
            s.color = GlassColor.alpha(t.warn, 0.9f); s.strokeWidth = 2.2f
            val cy = top + ROW / 2f
            c.drawCircle(40f, cy, 11f, s)
            c.drawLine(35f, cy, 45f, cy, s)
            name.color = t.warn
            name.getFontMetrics(fm)
            c.drawText(gs(R.string.glass_picker_remove), 64f, cy - (fm.ascent + fm.descent) / 2f, name)
        }
        if (items != null && items.isEmpty()) {
            note.color = t.text2
            c.drawText(gs(R.string.glass_picker_empty), W / 2f, (listTop + listBottom) / 2f - 4f, note)
            note.color = t.text3
            c.drawText(gs(R.string.glass_picker_empty_hint), W / 2f, (listTop + listBottom) / 2f + 18f, note)
        }
    }

    override fun drawLive(c: Canvas, nowMs: Long) {
        val list = items ?: return
        if (list.isEmpty()) return
        val t = env.theme
        c.save()
        c.clipRect(0f, listTop, W, listBottom)
        val first = max(0, (scroll / ROW).toInt())
        val last = min(list.size - 1, ((scroll + listBottom - listTop) / ROW).toInt())
        name.getFontMetrics(fm)
        for (i in first..last) {
            val it = list[i]
            val top = listTop + i * ROW - scroll
            val cy = top + ROW / 2f
            val sel = it.id == selected
            if (sel || pressedRow == i) {
                r.set(12f, top + 3f, W - 12f, top + ROW - 3f)
                p.shader = null
                p.color = GlassColor.alpha(t.glowAccent, if (pressedRow == i) 0.2f else 0.11f)
                c.drawRoundRect(r, 16f, 16f, p)
            }
            // a small glass chip holding the automation's glyph
            p.shader = null
            p.color = if (t.night) GlassColor.alpha(0xFFFFFFFF.toInt(), 0.08f) else 0xB3FFFFFF.toInt()
            c.drawCircle(40f, cy, 17f, p)
            s.color = if (t.night) 0x2EFFFFFF else 0x2E0B281E; s.strokeWidth = 1f
            c.drawCircle(40f, cy, 16.5f, s)
            c.save(); c.translate(40f - 11f, cy - 11f); c.scale(22f / 32f, 22f / 32f)
            val off = it.mode == "disabled"
            val glyph = it.glyph
            GlassGlyphs.draw(c, rowSlots[i].get(glyph, t, d * 22f / 32f, if (off) GlassGlyphs.Tone.CHROME else GlassGlyphs.Tone.TINT,
                if (off) 0 else GlassIcons.hue(glyph), bright = sel) { GlassIcons.outline(glyph) }, if (off) 110 else 255)
            c.restore()
            val right = if (sel) W - 52f else W - 96f
            val label = shownNames[i] ?: fit(it.name.ifBlank { gs(R.string.glass_automation) }, right - 72f).also { n -> shownNames[i] = n }
            name.color = if (it.mode == "disabled") t.text3 else t.text
            c.drawText(label, 68f, cy - (fm.ascent + fm.descent) / 2f, name)
            if (sel) {
                s.color = t.accent; s.strokeWidth = 2.6f
                c.drawLine(W - 40f, cy, W - 34f, cy + 6f, s); c.drawLine(W - 34f, cy + 6f, W - 24f, cy - 6f, s)
            } else {
                badge.color = t.text3
                badge.getFontMetrics(fm)
                c.drawText(modeLabel(it.mode), W - 26f, cy - (fm.ascent + fm.descent) / 2f, badge)
                name.getFontMetrics(fm)
            }
        }
        c.restore()
        drawFades(c, t)
    }

    // the list melts into the glass at its top and bottom edges while there's more to scroll
    private fun drawFades(c: Canvas, t: GlassTheme) {
        if (fadeNight != t.night) {
            fadeNight = t.night
            val g = GlassColor.alpha(t.glassBot, if (t.night) 0.9f else 0.85f)
            fadeTop = LinearGradient(0f, listTop, 0f, listTop + 18f, g, GlassColor.clear(t.glassBot), Shader.TileMode.CLAMP)
            fadeBottom = LinearGradient(0f, listBottom - 18f, 0f, listBottom, GlassColor.clear(t.glassBot), g, Shader.TileMode.CLAMP)
        }
        if (scroll > 0.5f) c.drawRect(8f, listTop, W - 8f, listTop + 18f, p.shade(fadeTop))
        if (scroll < maxScroll - 0.5f) c.drawRect(8f, listBottom - 18f, W - 8f, listBottom, p.shade(fadeBottom))
        p.shader = null
    }

    // trims whole code points, so an emoji in a name is never cut in half
    private fun fit(text: String, max: Float): String {
        if (name.measureText(text) <= max) return text
        var e = text
        while (e.codePointCount(0, e.length) > 1 && name.measureText("$e…") > max) e = e.substring(0, e.offsetByCodePoints(e.length, -1))
        return "$e…"
    }

    private fun modeLabel(mode: String) = when (mode) { "manual" -> gs(R.string.glass_mode_manual); "disabled" -> gs(R.string.glass_mode_off); else -> gs(R.string.glass_mode_auto) }

    override fun drawFront(c: Canvas) {}

    companion object {
        const val W = 440f
        private const val HEAD = 70f
        private const val ROW = 56f
        private const val FOOT_GAP = 14f
        private const val MAX_H = 430f

        fun heightFor(items: List<Item>?, canRemove: Boolean): Float {
            val rows = if (items.isNullOrEmpty()) 2.2f else items.size.toFloat()
            return min(MAX_H, HEAD + rows * ROW + (if (canRemove) ROW + FOOT_GAP else 10f) + 4f)
        }
    }
}
