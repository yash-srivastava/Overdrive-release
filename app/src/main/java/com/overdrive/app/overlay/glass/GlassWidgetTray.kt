package com.overdrive.app.overlay.glass

import com.overdrive.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max

/**
 * Arrange mode's tray of the widgets not on screen. It is only the glass, its title and the layout:
 * the arrange layer lays a live preview of each widget into its [tile] and draws the names under them,
 * so a preview can lift straight out of the tray. [setIds] re-flows the tiles into rows no wider than
 * [maxW] (design units) and morphs the glass to the new size.
 */
@SuppressLint("ViewConstructor")
class GlassWidgetTray(context: Context, env: GlassEnv, ticker: GlassTicker, private val maxW: () -> Float) :
    GlassWidgetView(context, env, ticker, MIN_W, EMPTY_H) {

    var ids: List<String> = emptyList()
        private set
    /** The glass the tray is heading to, in design units. */
    var targetW = MIN_W
        private set
    var targetH = EMPTY_H
        private set
    /** A widget held over the tray goes back into it when let go. */
    var dropTarget = false
        set(v) { if (field != v) { field = v; layersChanged() } }

    private val tiles = LinkedHashMap<String, RectF>()
    private val titleP = GlassFonts.paint(context, 600, 19f, 0)
    private val sub = GlassFonts.paint(context, 500, 14f, 0)
    private val note = GlassFonts.paint(context, 500, 15f, 0).apply { textAlign = Paint.Align.CENTER }
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()

    fun setIds(next: List<String>, instant: Boolean) {
        ids = next.toList()
        flow()
        setShellSize(targetW, targetH, false, instant)
        layersChanged()
    }

    /** Where [id]'s preview goes, in this tray's design units. */
    fun tile(id: String): RectF? = tiles[id]

    // rows filled left to right, each centred; a preview is its widget at [PREVIEW] of its size
    private fun flow() {
        tiles.clear()
        val inner = max(MIN_W, maxW()) - 2f * PAD
        val rows = ArrayList<MutableList<String>>()
        var used = 0f
        for (id in ids) {
            val w = GlassWidgets.size(id).first * PREVIEW
            if (rows.isEmpty() || (used + GAP + w > inner && rows.last().isNotEmpty())) { rows.add(ArrayList()); used = -GAP }
            rows.last().add(id)
            used += GAP + w
        }
        val widths = rows.map { row -> row.sumOf { (GlassWidgets.size(it).first * PREVIEW).toDouble() }.toFloat() + GAP * (row.size - 1) }
        targetW = max(MIN_W, (widths.maxOrNull() ?: 0f) + 2f * PAD)
        var y = HEAD
        for ((i, row) in rows.withIndex()) {
            var x = (targetW - widths[i]) / 2f
            var rowH = 0f
            for (id in row) {
                val (w, h) = GlassWidgets.size(id)
                tiles[id] = RectF(x, y, x + w * PREVIEW, y + h * PREVIEW)
                x += w * PREVIEW + GAP
                rowH = max(rowH, h * PREVIEW)
            }
            y += rowH + CAPTION + (if (i < rows.size - 1) ROW_GAP else 0f)
        }
        targetH = if (ids.isEmpty()) EMPTY_H else y + BOTTOM
    }

    override fun drawBack(c: Canvas) {
        val t = env.theme
        titleP.color = t.text
        c.drawText(gs(R.string.glass_tray_title), 22f, 32f, titleP)
        sub.color = if (dropTarget) t.accent else t.text2
        c.drawText(if (dropTarget) gs(R.string.glass_tray_drop) else gs(R.string.glass_tray_hint), 22f, 51f, sub)
        if (ids.isEmpty()) {
            note.color = t.text2
            c.drawText(gs(R.string.glass_tray_all_shown), shellW / 2f, HEAD + 18f, note)
        }
    }

    override fun drawLive(c: Canvas, nowMs: Long) {}

    // a widget held over the tray lights its edge
    override fun drawFront(c: Canvas) {
        if (!dropTarget) return
        s.color = GlassColor.alpha(env.theme.glowAccent, 0.9f)
        s.strokeWidth = 2f
        r.set(1f, 1f, shellW - 1f, shellH - 1f)
        c.drawRoundRect(r, GlassWidgetView.RADIUS - 1f, GlassWidgetView.RADIUS - 1f, s)
    }

    companion object {
        /** A preview's size against its widget's. */
        const val PREVIEW = 0.42f
        /** The caption under each preview, in design units. */
        const val CAPTION = 30f
        private const val PAD = 20f
        private const val GAP = 18f
        private const val ROW_GAP = 12f
        private const val HEAD = 66f
        private const val BOTTOM = 12f
        private const val MIN_W = 400f
        private const val EMPTY_H = 110f
    }
}
