package com.overdrive.app.overlay.glass

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.min

/**
 * Glass battery cell (battery + charging): brushed-metal cap with a + terminal (or a ridged fuel
 * cap), metal base, clear glass body and glowing liquid. [drawBack]/[drawFront] are static and
 * baked by the widget; [drawLive] draws only the liquid, surface, halo and optional limit ring.
 * All units are design px.
 */
class CellPainter(private val theme: GlassTheme, x: Float, y: Float, w: Float, h: Float, fuelSecond: Boolean) {

    enum class Kind { EV, FUEL }

    inner class Cell(val kind: Kind, val x: Float, cw: Float, y: Float, h: Float) {
        val rx = cw / 2f
        val cx = x + rx
        val ry = rx * 0.3f
        val nubRx = if (kind == Kind.FUEL) rx * 0.58f else rx * 0.34f
        val nubRy = nubRx * 0.3f
        val nubH = if (kind == Kind.FUEL) 8f else 6f
        val yNub = y + nubRy
        val yCapTop = yNub + nubH
        val yA = yCapTop + CAP_H
        val yBot = y + h - ry
        val yB = yBot - BASE_H
        val lrx = rx - 3f
        val lry = ry * lrx / rx

        var level = 0f
        var color = 0
        var liquidShader: LinearGradient? = null
        var depth: LinearGradient? = null
        var surfaceShader: RadialGradient? = null
        var coreShader: RadialGradient? = null
        var haloShader: RadialGradient? = null

        fun levelY(f: Float) = yB - f.coerceIn(0f, 1f) * (yB - yA - 2f)
    }

    val cells: List<Cell>
    private val night = theme.night

    init {
        val gap = if (fuelSecond) 10f else 0f
        val n = if (fuelSecond) 2 else 1
        val cw = (w - gap * (n - 1)) / n
        cells = (0 until n).map { i -> Cell(if (i == 0) Kind.EV else Kind.FUEL, x + i * (cw + gap), cw, y, h) }
    }

    // ── static back: shadows and the glass interior ──────────────────────────

    fun drawBack(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (cell in cells) {
            p.shade(GlassWidgetView.ellipseGradient(cell.cx + 8f, (cell.yA + cell.yBot) / 2f + 9f, cell.rx * 1.3f, (cell.yBot - cell.yA) / 2f + 16f,
                intArrayOf(GlassColor.alpha(theme.shadow, theme.shadowA * 0.8f), GlassColor.alpha(theme.shadow, theme.shadowA * 0.36f), GlassColor.clear(theme.shadow)), floatArrayOf(0f, 0.55f, 1f)))
            c.drawOval(cell.cx + 8f - cell.rx * 1.3f, (cell.yA + cell.yBot) / 2f + 9f - ((cell.yBot - cell.yA) / 2f + 16f),
                cell.cx + 8f + cell.rx * 1.3f, (cell.yA + cell.yBot) / 2f + 9f + ((cell.yBot - cell.yA) / 2f + 16f), p)
            p.shade(GlassWidgetView.ellipseGradient(cell.cx + 5f, cell.yBot + 3f, cell.rx * 1.18f, cell.ry * 1.25f,
                intArrayOf(GlassColor.alpha(theme.shadow, min(1f, theme.shadowA * 1.3f)), GlassColor.clear(theme.shadow)), floatArrayOf(0f, 1f)))
            c.drawOval(cell.cx + 5f - cell.rx * 1.18f, cell.yBot + 3f - cell.ry * 1.25f, cell.cx + 5f + cell.rx * 1.18f, cell.yBot + 3f + cell.ry * 1.25f, p)
        }
        for (cell in cells) {
            p.shade(LinearGradient(cell.x, 0f, cell.x + cell.rx * 2f, 0f,
                if (night) intArrayOf(GlassColor.alpha(WHITE, 0.15f), GlassColor.alpha(WHITE, 0.05f), GlassColor.alpha(BLACK, 0.16f), GlassColor.alpha(WHITE, 0.05f), GlassColor.alpha(WHITE, 0.12f))
                else intArrayOf(GlassColor.alpha(INK_DAY, 0.14f), GlassColor.alpha(INK_DAY, 0.05f), GlassColor.alpha(WHITE, 0.4f), GlassColor.alpha(INK_DAY, 0.05f), GlassColor.alpha(INK_DAY, 0.15f)),
                floatArrayOf(0f, if (night) 0.14f else 0.16f, 0.5f, if (night) 0.86f else 0.84f, 1f), Shader.TileMode.CLAMP))
            c.drawRect(cell.x, cell.yA, cell.x + cell.rx * 2f, cell.yB, p)
        }
    }

    // ── live: liquid, surface, halo, limit ring ──────────────────────────────

    private val live = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val r = RectF()
    private val rimPath = Path()
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    fun setColor(i: Int, color: Int) {
        val cell = cells[i]
        if (cell.color == color) return
        cell.color = color
        val k = if (night) intArrayOf(GlassColor.darken(color, 0.5f), GlassColor.darken(color, 0.18f), GlassColor.lighten(color, 0.38f), color, GlassColor.darken(color, 0.22f), GlassColor.darken(color, 0.55f))
        else intArrayOf(GlassColor.darken(color, 0.34f), GlassColor.darken(color, 0.1f), GlassColor.lighten(color, 0.3f), color, GlassColor.darken(color, 0.15f), GlassColor.darken(color, 0.4f))
        cell.liquidShader = LinearGradient(cell.cx - cell.lrx, 0f, cell.cx + cell.lrx, 0f, k, floatArrayOf(0f, 0.12f, 0.3f, 0.44f, 0.8f, 1f), Shader.TileMode.CLAMP)
        cell.surfaceShader = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.lighten(color, if (night) 0.62f else 0.5f), GlassColor.lighten(color, if (night) 0.28f else 0.2f), color),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cell.coreShader = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(GlassColor.lighten(color, 0.65f), 0.5f), GlassColor.clear(GlassColor.lighten(color, 0.65f))), null, Shader.TileMode.CLAMP)
        cell.haloShader = RadialGradient(0f, 0f, 1f, intArrayOf(GlassColor.alpha(color, if (night) 0.36f else 0.2f), GlassColor.alpha(color, if (night) 0.13f else 0.07f), GlassColor.clear(color)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    fun setLevel(i: Int, f: Float) { cells[i].level = f.coerceIn(0f, 1f) }

    /** Something drawn inside a cell's liquid (the charging sparks); kept as one object, not a per-frame lambda. */
    fun interface Particles { fun draw(c: Canvas, cell: Cell) }

    /** Liquid for every cell. [agitation] (spring velocity) swells the surface while it moves. */
    fun drawLive(c: Canvas, agitation: FloatArray?, particles: Particles? = null) {
        for (i in cells.indices) {
            val cell = cells[i]
            val sy = cell.levelY(cell.level)
            val hh = cell.yB - sy
            // light from the liquid spilling onto the glass pane
            if (cell.level > 0.001f) {
                val hrx = cell.rx * 1.35f + 12f; val hry = hh / 2f + 24f
                // Keep the radial shader in unit space. Updating a Shader local
                // matrix every frame makes HWUI rebuild its native shader state;
                // transform the canvas instead.
                c.save()
                c.translate(cell.cx, sy + hh / 2f)
                c.scale(hrx, hry)
                live.shade(cell.haloShader)
                live.alpha = (min(1f, cell.level * 5f) * 255).toInt()
                c.drawCircle(0f, 0f, 1f, live)
                c.restore()
                live.alpha = 255
            }
            if (cell.level <= 0.006f) continue
            c.save()
            c.clipRect(cell.cx - cell.lrx, sy, cell.cx + cell.lrx, cell.yB)
            live.shade(cell.liquidShader)
            c.drawRect(cell.cx - cell.lrx, sy, cell.cx + cell.lrx, cell.yB, live)
            live.shade(depthShader(cell, sy))
            c.drawRect(cell.cx - cell.lrx, sy, cell.cx + cell.lrx, cell.yB, live)
            m.setScale(cell.lrx * 1.1f, 16f); m.postTranslate(cell.cx - cell.lrx * 0.1f, sy + 12f)
            cell.coreShader?.setLocalMatrix(m)
            live.shade(cell.coreShader)
            c.drawRect(cell.cx - cell.lrx, sy, cell.cx + cell.lrx, cell.yB, live)
            particles?.draw(c, cell)
            c.restore()

            val ag = if (agitation != null && i < agitation.size) agitation[i] else 0f
            val ry = cell.lry + min(2.2f, abs(ag) * 2f)
            // Preserve the offset highlight geometry while moving the radial
            // transform to the canvas: per-frame setLocalMatrix() causes
            // avoidable native shader updates.
            val shaderCx = cell.cx - cell.lrx * 0.28f
            val shaderCy = sy - ry * 0.24f
            val shaderRx = cell.lrx * 1.5f
            val shaderRy = ry * 1.5f
            c.save()
            c.translate(shaderCx, shaderCy)
            c.scale(shaderRx, shaderRy)
            live.shade(cell.surfaceShader)
            live.alpha = 245
            c.drawOval(
                (cell.cx - cell.lrx - shaderCx) / shaderRx,
                (sy - ry - shaderCy) / shaderRy,
                (cell.cx + cell.lrx - shaderCx) / shaderRx,
                (sy + ry - shaderCy) / shaderRy,
                live
            )
            c.restore()
            live.alpha = 255
            live.shader = null
            rimPath.rewind()
            r.set(cell.cx - cell.lrx + 0.5f, sy - ry + 0.3f, cell.cx + cell.lrx - 0.5f, sy + ry - 0.3f)
            rimPath.addArc(r, 0f, 180f)
            limitPaint.color = GlassColor.alpha(GlassColor.lighten(cell.color, 0.78f), 0.85f)
            limitPaint.strokeWidth = 1f
            c.drawPath(rimPath, limitPaint)
        }
    }

    // one unit (0→1 down) gradient per cell, stretched from the liquid top to the base; re-placed only as the level moves
    private fun depthShader(cell: Cell, top: Float): LinearGradient {
        val g = cell.depth ?: LinearGradient(0f, 0f, 0f, 1f, intArrayOf(GlassColor.alpha(WHITE, 0.26f), GlassColor.clear(WHITE), GlassColor.alpha(BLACK, if (night) 0.3f else 0.14f)),
            floatArrayOf(0f, 0.22f, 1f), Shader.TileMode.CLAMP).also { cell.depth = it }
        m.setScale(1f, maxOf(0.01f, cell.yB - top)); m.postTranslate(0f, top)
        g.setLocalMatrix(m)
        return g
    }

    /** Etched charge-limit ring around cell 0: solid in front, dashed behind, ticks outside. */
    fun drawLimit(c: Canvas, frac: Float) {
        val cell = cells[0]
        val yy = cell.levelY(frac)
        val rr = cell.rx + 1.2f
        val ry = cell.ry * rr / cell.rx
        r.set(cell.cx - rr, yy - ry, cell.cx + rr, yy + ry)
        limitPaint.pathEffect = DASH
        limitPaint.strokeWidth = 0.8f
        limitPaint.color = GlassColor.alpha(if (night) WHITE else INK_TEXT, 0.35f)
        c.drawArc(r, 180f, 180f, false, limitPaint)
        limitPaint.pathEffect = null
        limitPaint.color = GlassColor.alpha(if (night) BLACK else WHITE, if (night) 0.55f else 0.95f)
        limitPaint.strokeWidth = 1.2f
        r.offset(0f, 1.3f); c.drawArc(r, 0f, 180f, false, limitPaint); r.offset(0f, -1.3f)
        limitPaint.color = GlassColor.alpha(if (night) WHITE else INK_TEXT, if (night) 0.92f else 0.8f)
        limitPaint.strokeWidth = 1.5f
        c.drawArc(r, 0f, 180f, false, limitPaint)
        c.drawLine(cell.x - 7f, yy, cell.x - 2f, yy, limitPaint)
        c.drawLine(cell.x + cell.rx * 2f + 2f, yy, cell.x + cell.rx * 2f + 7f, yy, limitPaint)
    }

    // ── static front: glass edges, speculars, metal base and cap, terminal, icon ──

    fun drawFront(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val metalColors = if (night) intArrayOf(0xFF1B2221.toInt(), 0xFF3A4543.toInt(), 0xFF8E9C98.toInt(), 0xFF56625F.toInt(), 0xFF29322F.toInt(), 0xFF4B5654.toInt(), 0xFF171D1C.toInt())
        else intArrayOf(0xFF8C9793.toInt(), 0xFFC4CDC8.toInt(), WHITE, 0xFFD6DDD9.toInt(), 0xFFA8B2AE.toInt(), 0xFFCCD4D0.toInt(), 0xFF86908C.toInt())
        val metalStops = floatArrayOf(0f, 0.1f, 0.26f, 0.37f, 0.64f, 0.87f, 1f)
        val ink = if (night) 0xFF050807.toInt() else 0xFF27322E.toInt()
        for (cell in cells) {
            val left = cell.x; val right = cell.x + cell.rx * 2f
            // glass edges and speculars
            s.shader = null; s.strokeWidth = 1.2f; s.color = GlassColor.alpha(WHITE, if (night) 0.45f else 1f)
            c.drawLine(left + 0.7f, cell.yA, left + 0.7f, cell.yB, s)
            s.strokeWidth = 1f; s.color = GlassColor.alpha(if (night) WHITE else INK_DAY, if (night) 0.14f else 0.2f)
            c.drawLine(right - 0.7f, cell.yA, right - 0.7f, cell.yB, s)
            p.shade(LinearGradient(0f, cell.yA + 5f, 0f, cell.yB - 1f, intArrayOf(GlassColor.clear(WHITE), GlassColor.alpha(WHITE, 0.75f), GlassColor.alpha(WHITE, 0.3f), GlassColor.clear(WHITE)), floatArrayOf(0f, 0.1f, 0.6f, 1f), Shader.TileMode.CLAMP))
            r.set(left + cell.rx * 0.4f, cell.yA + 5f, left + cell.rx * 0.4f + 3f, cell.yB - 1f); c.drawRoundRect(r, 1.5f, 1.5f, p)
            p.shade(LinearGradient(left + cell.rx * 0.2f, 0f, left + cell.rx * 0.75f, 0f, intArrayOf(GlassColor.clear(WHITE), GlassColor.alpha(WHITE, 0.12f), GlassColor.clear(WHITE)), null, Shader.TileMode.CLAMP))
            c.drawRect(left + cell.rx * 0.2f, cell.yA + 3f, left + cell.rx * 0.75f, cell.yB, p)
            p.shader = null; p.color = GlassColor.alpha(WHITE, if (night) 0.16f else 0.5f)
            r.set(left + cell.rx * 1.62f, cell.yA + 8f, left + cell.rx * 1.62f + 1.5f, cell.yB - 6f); c.drawRoundRect(r, 0.75f, 0.75f, p)

            // metal base: band between the front arcs at yB and yBot
            p.shade(LinearGradient(left, 0f, right, 0f, metalColors, metalStops, Shader.TileMode.CLAMP))
            c.drawPath(band(cell.cx, cell.rx, cell.ry, cell.yB, cell.yBot), p)
            p.shader = null
            s.strokeWidth = 0.9f; s.color = GlassColor.alpha(WHITE, if (night) 0.3f else 0.9f)
            c.drawPath(frontArc(cell.cx, cell.rx, cell.ry, cell.yB), s)
            s.strokeWidth = 0.8f; s.color = GlassColor.alpha(ink, 0.55f)
            c.drawPath(frontArc(cell.cx, cell.rx, cell.ry, cell.yB + 1.2f), s)

            // metal cap, gasket, face and its crimped ring
            p.shade(LinearGradient(left, 0f, right, 0f, metalColors, metalStops, Shader.TileMode.CLAMP))
            c.drawPath(band(cell.cx, cell.rx, cell.ry, cell.yCapTop, cell.yA), p)
            p.shader = null
            s.strokeWidth = 1.6f; s.color = GlassColor.alpha(ink, 0.8f)
            c.drawPath(frontArc(cell.cx, cell.rx, cell.ry, cell.yA), s)
            p.shade(faceGradient(cell.cx - cell.rx, cell.yCapTop - cell.ry, cell.cx + cell.rx, cell.yCapTop + cell.ry))
            c.drawOval(cell.cx - cell.rx, cell.yCapTop - cell.ry, cell.cx + cell.rx, cell.yCapTop + cell.ry, p)
            p.shader = null
            s.strokeWidth = 1f; s.color = GlassColor.alpha(if (night) WHITE else INK_DAY, 0.14f)
            c.drawOval(cell.cx - cell.rx * 0.8f, cell.yCapTop - cell.ry * 0.8f, cell.cx + cell.rx * 0.8f, cell.yCapTop + cell.ry * 0.8f, s)
            s.strokeWidth = 0.9f; s.color = GlassColor.alpha(WHITE, if (night) 0.45f else 1f)
            r.set(cell.cx - cell.rx + 0.6f, cell.yCapTop - cell.ry + 0.3f, cell.cx + cell.rx - 0.6f, cell.yCapTop + cell.ry - 0.3f)
            c.drawArc(r, 180f, 180f, false, s)

            // + terminal or ridged fuel cap
            p.shade(LinearGradient(cell.cx - cell.nubRx, 0f, cell.cx + cell.nubRx, 0f, metalColors, metalStops, Shader.TileMode.CLAMP))
            c.drawPath(band(cell.cx, cell.nubRx, cell.nubRy, cell.yNub, cell.yCapTop), p)
            p.shader = null
            if (cell.kind == Kind.FUEL) {
                s.strokeWidth = 0.9f; s.color = GlassColor.alpha(ink, 0.45f)
                for (k in -3..3) {
                    val kx = cell.cx + k * cell.nubRx * 0.27f
                    c.drawLine(kx, cell.yNub + 1.5f, kx, cell.yCapTop + cell.nubRy * (1f - abs(k) * 0.08f) - 0.5f, s)
                }
            }
            p.shade(faceGradient(cell.cx - cell.nubRx, cell.yNub - cell.nubRy, cell.cx + cell.nubRx, cell.yNub + cell.nubRy))
            c.drawOval(cell.cx - cell.nubRx, cell.yNub - cell.nubRy, cell.cx + cell.nubRx, cell.yNub + cell.nubRy, p)
            p.shader = null

            // engraved icon on the cap band
            val iy = cell.yCapTop + cell.ry + (cell.yA - cell.yCapTop) / 2f
            val icon = GlassPaths.svg(if (cell.kind == Kind.FUEL) PUMP else BOLT)
            icon.fillType = if (cell.kind == Kind.FUEL) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            c.save(); c.translate(cell.cx + 0.6f, iy + 0.7f)
            p.color = GlassColor.alpha(WHITE, if (night) 0.18f else 0.8f); c.drawPath(icon, p)
            c.restore()
            c.save(); c.translate(cell.cx, iy)
            p.color = GlassColor.alpha(ink, if (night) 0.75f else 0.6f); c.drawPath(icon, p)
            c.restore()
        }
    }

    private fun faceGradient(l: Float, t: Float, rr: Float, b: Float) = LinearGradient(l, t, rr, b,
        if (night) intArrayOf(0xFFA4B1AD.toInt(), 0xFF5A6663.toInt(), 0xFF2B3331.toInt()) else intArrayOf(WHITE, 0xFFDCE2DF.toInt(), 0xFFAAB4B0.toInt()),
        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)

    private val bandPath = Path()
    private val arcPath = Path()

    /** The side band of a cylinder slice seen from above: straight sides, convex front arcs. */
    private fun band(cx: Float, rx: Float, ry: Float, y0: Float, y1: Float): Path {
        bandPath.rewind()
        bandPath.moveTo(cx - rx, y0)
        bandPath.lineTo(cx - rx, y1)
        r.set(cx - rx, y1 - ry, cx + rx, y1 + ry)
        bandPath.arcTo(r, 180f, -180f, false)
        bandPath.lineTo(cx + rx, y0)
        bandPath.close()
        return bandPath
    }

    private fun frontArc(cx: Float, rx: Float, ry: Float, y: Float): Path {
        arcPath.rewind()
        r.set(cx - rx, y - ry, cx + rx, y + ry)
        arcPath.addArc(r, 0f, 180f)
        return arcPath
    }

    companion object {
        const val CAP_H = 12f
        const val BASE_H = 9f
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val INK_DAY = 0xFF0B281E.toInt()
        const val INK_TEXT = 0xFF0B1A15.toInt()
        private val DASH = android.graphics.DashPathEffect(floatArrayOf(2f, 2f), 0f)
        const val BOLT = "M1.3 -4.6 L-2.4 0.7 H0 L-1.2 4.6 L2.5 -0.8 H0.2 Z"
        const val PUMP = "M-2.9 -4 H1.3 V4 H-2.9 Z M-2 -3.1 V-1 H0.4 V-3.1 Z M1.3 -2.2 L2.9 -1 V2.4 A0.8 0.8 0 0 1 1.7 2.4 V0.7 H1.3 Z"
    }
}
