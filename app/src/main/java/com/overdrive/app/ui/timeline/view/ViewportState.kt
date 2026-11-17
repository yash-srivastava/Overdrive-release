package com.overdrive.app.ui.timeline.view

import com.overdrive.app.ui.view.ZoomableVideoView.Quadrant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Displayed overshoot for a raw overshoot [x] past an edge of a [d]-sized dimension. */
fun rubberBand(x: Float, d: Float): Float = if (d <= 0f) 0f else (x * d * 0.55f) / (d + 0.55f * abs(x))

/** Closed-form damped spring of unit mass; response is the undamped period in seconds, damping the ratio (<= 1). */
class Spring(initial: Float, private val epsilon: Float) {
    var value = initial
        private set
    var target = initial
        private set
    var velocity = 0f
        private set
    var running = false
        private set
    private var paused = false
    private var startNs = 0L
    private var x0 = 0.0
    private var v0 = 0.0
    private var omega = 0.0
    private var zeta = 1.0

    fun snap(to: Float) {
        value = to
        target = to
        velocity = 0f
        running = false
        paused = false
    }

    /** Retargets from the presented value; keeps the current velocity unless [initialVelocity] is given. */
    fun animateTo(
        to: Float,
        nowNs: Long,
        response: Float = RESPONSE,
        damping: Float = 1f,
        initialVelocity: Float = Float.NaN,
    ) {
        update(nowNs)
        val v = if (initialVelocity.isNaN()) velocity else initialVelocity
        target = to
        paused = false
        startNs = nowNs
        x0 = (value - to).toDouble()
        v0 = v.toDouble()
        omega = 2.0 * PI / max(response, 0.01f)
        zeta = damping.toDouble().coerceIn(0.05, 1.0)
        velocity = v
        running = true
        if (settled(x0, v0)) snap(to)
    }

    /** Stops at the presented value; [resume] continues to the same target. */
    fun pause(nowNs: Long) {
        if (!running) return
        update(nowNs)
        if (!running) return
        running = false
        paused = true
        velocity = 0f
    }

    fun resume(nowNs: Long) {
        if (paused) animateTo(target, nowNs, initialVelocity = 0f)
    }

    /** Advances to [nowNs]; true while still moving. */
    fun update(nowNs: Long): Boolean {
        if (!running) return false
        val t = max(0L, nowNs - startNs) / 1e9
        val x: Double
        val v: Double
        if (zeta >= 1.0) {
            val e = exp(-omega * t)
            val b = v0 + omega * x0
            x = (x0 + b * t) * e
            v = (v0 - omega * b * t) * e
        } else {
            val wd = omega * sqrt(1.0 - zeta * zeta)
            val e = exp(-zeta * omega * t)
            val c = (v0 + zeta * omega * x0) / wd
            val cs = cos(wd * t)
            val sn = sin(wd * t)
            x = e * (x0 * cs + c * sn)
            v = e * ((c * wd - zeta * omega * x0) * cs - (x0 * wd + zeta * omega * c) * sn)
        }
        if (settled(x, v)) {
            snap(target)
            return false
        }
        value = (target + x).toFloat()
        velocity = v.toFloat()
        return true
    }

    private fun settled(x: Double, v: Double) = abs(x) < epsilon && abs(v) < epsilon * 10.0

    companion object {
        const val RESPONSE = 0.35f
    }
}

/** Camera crop, pinch zoom and pan of one video surface. Not thread-safe; values are as of the last [update]. */
class ViewportState {
    var layout = TileLayout.STANDARD
        private set
    var camera = Quadrant.ALL
        private set
    var viewW = 0f
        private set
    var viewH = 0f
        private set
    var videoW = 0
        private set
    var videoH = 0
        private set

    private val rx = Spring(0f, EPS)
    private val ry = Spring(0f, EPS)
    private val rw = Spring(1f, EPS)
    private val rh = Spring(1f, EPS)
    private val zoom = Spring(1f, EPS)
    private val cx = Spring(0.5f, EPS)
    private val cy = Spring(0.5f, EPS)
    private val all = arrayOf(rx, ry, rw, rh, zoom, cx, cy)
    private val crop = arrayOf(rx, ry, rw, rh)

    private var touching = false
    private var modified = false
    private var scaled = false
    private var rawZoom = 1f
    private var rawCx = 0.5f
    private var rawCy = 0.5f
    private var focalX = 0f
    private var focalY = 0f
    private val fit = FloatArray(2)

    private val live get() = touching && modified

    fun setViewSize(w: Float, h: Float) {
        viewW = max(0f, w)
        viewH = max(0f, h)
        clampCenter()
    }

    fun setVideoSize(w: Int, h: Int) {
        videoW = max(0, w)
        videoH = max(0, h)
        clampCenter()
    }

    /** Presented crop region. */
    fun region(): Region = Region(rx.value, ry.value, max(MIN_EXTENT, rw.value), max(MIN_EXTENT, rh.value))

    fun presentedZoom(): Float = if (live) bandZoom(rawZoom) else zoom.value

    fun zoomed(): Boolean = presentedZoom() > ZOOMED

    /** Presented placement of the crop region in the view; returns that region. */
    fun place(out: Placement): Region {
        val r = region()
        CameraRegions.fitSize(viewW, viewH, videoW, videoH, r, fit)
        val z = presentedZoom()
        val px: Float
        val py: Float
        if (live) {
            px = bandCenter(rawCx, fit[0] * z, viewW)
            py = bandCenter(rawCy, fit[1] * z, viewH)
        } else {
            px = cx.value
            py = cy.value
        }
        CameraRegions.place(viewW, viewH, fit[0], fit[1], z, px, py, out)
        return r
    }

    /** Advances every animation to [nowNs]; true while any is moving. */
    fun update(nowNs: Long): Boolean {
        var moving = false
        for (s in all) moving = s.update(nowNs) or moving
        return moving
    }

    /** A clip's layout takes effect on its first frame: keeps the solo camera when the layout has it, else ALL. */
    fun applyLayout(next: TileLayout): Boolean {
        if (next == layout) return false
        layout = next
        val before = camera
        camera = CameraRegions.normalize(next, camera)
        snapCrop(CameraRegions.regionFor(next, camera))
        zoomTo(1f, 0.5f, 0.5f, false, 0L)
        return camera != before
    }

    fun setCamera(q: Quadrant, animate: Boolean, nowNs: Long): Boolean {
        val next = CameraRegions.normalize(layout, q)
        val changed = next != camera
        camera = next
        val r = CameraRegions.regionFor(layout, next)
        if (animate) {
            rx.animateTo(r.x, nowNs)
            ry.animateTo(r.y, nowNs)
            rw.animateTo(r.w, nowNs)
            rh.animateTo(r.h, nowNs)
        } else {
            snapCrop(r)
        }
        zoomTo(1f, 0.5f, 0.5f, animate, nowNs)
        return changed
    }

    fun resetZoom(animate: Boolean, nowNs: Long) = zoomTo(1f, 0.5f, 0.5f, animate, nowNs)

    /** Single tap: solos the camera under the point, or returns to ALL when one is soloed. */
    fun tap(x: Float, y: Float, animate: Boolean, nowNs: Long): Quadrant {
        val next = if (camera != Quadrant.ALL) {
            Quadrant.ALL
        } else {
            val p = Placement()
            val r = place(p)
            CameraRegions.cameraAt(layout, r, p, x, y)
        }
        setCamera(next, animate, nowNs)
        return camera
    }

    /** Double tap: 2x with the tapped point kept under the finger, or back to 1x when zoomed. */
    fun doubleTap(x: Float, y: Float, animate: Boolean, nowNs: Long) {
        val p = Placement()
        place(p)
        if (zoom.target > ZOOMED || presentedZoom() > ZOOMED) {
            zoomTo(1f, 0.5f, 0.5f, animate, nowNs)
            return
        }
        if (p.width <= 0f || p.height <= 0f) return
        val ux = (x - p.left) / p.width
        val uy = (y - p.top) / p.height
        val ex = fit[0] * DOUBLE_TAP_ZOOM
        val ey = fit[1] * DOUBLE_TAP_ZOOM
        val tx = clampAxis(ux - (x - viewW / 2f) / ex, ex, viewW)
        val ty = clampAxis(uy - (y - viewH / 2f) / ey, ey, viewH)
        zoomTo(DOUBLE_TAP_ZOOM, tx, ty, animate, nowNs)
    }

    /** Finger down: every animation stops at its presented value. */
    fun gestureBegin(nowNs: Long) {
        for (s in all) s.pause(nowNs)
        touching = true
        modified = false
        scaled = false
        rawZoom = zoom.value
        rawCx = cx.value
        rawCy = cy.value
    }

    /** Pinch step: the content point under the focal point stays under it (rubber-banded past 1x..4x). */
    fun scale(factor: Float, fx: Float, fy: Float, nowNs: Long) {
        if (!touching || !(factor > 0f) || factor.isInfinite()) return
        beginEdit(nowNs)
        CameraRegions.fitSize(viewW, viewH, videoW, videoH, region(), fit)
        if (fit[0] <= 0f || fit[1] <= 0f) return
        val z0 = rawZoom
        val z1 = (z0 * factor).coerceIn(RAW_MIN_ZOOM, RAW_MAX_ZOOM)
        val dx = fx - viewW / 2f
        val dy = fy - viewH / 2f
        rawCx += dx / (fit[0] * z0) - dx / (fit[0] * z1)
        rawCy += dy / (fit[1] * z0) - dy / (fit[1] * z1)
        rawZoom = z1
        focalX = fx
        focalY = fy
        scaled = true
    }

    /** Finger movement in view px; only while zoomed or pinching. */
    fun pan(dx: Float, dy: Float, nowNs: Long) {
        if (!touching) return
        if (!scaled && presentedZoom() <= ZOOMED) return
        beginEdit(nowNs)
        CameraRegions.fitSize(viewW, viewH, videoW, videoH, region(), fit)
        if (fit[0] <= 0f || fit[1] <= 0f) return
        rawCx -= dx / (fit[0] * rawZoom)
        rawCy -= dy / (fit[1] * rawZoom)
    }

    /** Fingers up: springs back inside 1x..4x and the pan bounds; a fling lands at its projected point. */
    fun gestureEnd(vx: Float, vy: Float, nowNs: Long) {
        if (!touching) return
        touching = false
        if (!modified) {
            for (s in all) s.resume(nowNs)
            return
        }
        modified = false
        CameraRegions.fitSize(viewW, viewH, videoW, videoH, region(), fit)
        val zp = bandZoom(rawZoom)
        val exP = fit[0] * zp
        val eyP = fit[1] * zp
        val cxp = bandCenter(rawCx, exP, viewW)
        val cyp = bandCenter(rawCy, eyP, viewH)
        val zt = zp.coerceIn(MIN_ZOOM, MAX_ZOOM)
        var tx = cxp
        var ty = cyp
        if (zt != zp && exP > 0f && eyP > 0f) {
            val fdx = focalX - viewW / 2f
            val fdy = focalY - viewH / 2f
            tx = cxp + fdx / exP - fdx / (fit[0] * zt)
            ty = cyp + fdy / eyP - fdy / (fit[1] * zt)
        }
        val ex = fit[0] * zt
        val ey = fit[1] * zt
        val flingX = vx != 0f && ex > viewW
        val flingY = vy != 0f && ey > viewH
        if (flingX) tx -= projection(vx) / ex
        if (flingY) ty -= projection(vy) / ey
        tx = clampAxis(tx, ex, viewW)
        ty = clampAxis(ty, ey, viewH)
        zoom.snap(zp)
        cx.snap(cxp)
        cy.snap(cyp)
        zoom.animateTo(zt, nowNs, initialVelocity = 0f)
        cx.animateTo(tx, nowNs, damping = if (flingX) FLING_DAMPING else 1f,
            initialVelocity = if (flingX) -vx / ex else 0f)
        cy.animateTo(ty, nowNs, damping = if (flingY) FLING_DAMPING else 1f,
            initialVelocity = if (flingY) -vy / ey else 0f)
        for (s in crop) s.resume(nowNs)
        rawZoom = zt
        rawCx = tx
        rawCy = ty
    }

    private fun beginEdit(nowNs: Long) {
        if (modified) return
        if (zoom.running || cx.running || cy.running) {
            zoom.pause(nowNs)
            cx.pause(nowNs)
            cy.pause(nowNs)
            rawZoom = zoom.value
            rawCx = cx.value
            rawCy = cy.value
        }
        modified = true
    }

    private fun zoomTo(z: Float, tx: Float, ty: Float, animate: Boolean, nowNs: Long) {
        if (animate) {
            zoom.animateTo(z, nowNs)
            cx.animateTo(tx, nowNs)
            cy.animateTo(ty, nowNs)
        } else {
            zoom.snap(z)
            cx.snap(tx)
            cy.snap(ty)
        }
        rawZoom = z
        rawCx = tx
        rawCy = ty
        modified = false
    }

    private fun snapCrop(r: Region) {
        rx.snap(r.x)
        ry.snap(r.y)
        rw.snap(r.w)
        rh.snap(r.h)
    }

    private fun clampCenter() {
        if (live) return
        CameraRegions.fitSize(viewW, viewH, videoW, videoH, CameraRegions.regionFor(layout, camera), fit)
        val tx = clampAxis(cx.target, fit[0] * zoom.target, viewW)
        val ty = clampAxis(cy.target, fit[1] * zoom.target, viewH)
        if (tx != cx.target) cx.snap(tx)
        if (ty != cy.target) cy.snap(ty)
    }

    private fun bandZoom(z: Float): Float = when {
        z < MIN_ZOOM -> MIN_ZOOM * exp(rubberBand(ln(z / MIN_ZOOM), ZOOM_BAND))
        z > MAX_ZOOM -> MAX_ZOOM * exp(rubberBand(ln(z / MAX_ZOOM), ZOOM_BAND))
        else -> z
    }

    private fun bandCenter(c: Float, extent: Float, view: Float): Float {
        if (extent <= 0f) return 0.5f
        val lo = CameraRegions.centerMin(extent, view)
        val clamped = c.coerceIn(lo, 1f - lo)
        return clamped + rubberBand((c - clamped) * extent, view) / extent
    }

    private fun clampAxis(c: Float, extent: Float, view: Float): Float {
        val lo = CameraRegions.centerMin(extent, view)
        return c.coerceIn(lo, 1f - lo)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        const val DOUBLE_TAP_ZOOM = 2f
        const val ZOOMED = 1.01f
        const val FLING_DAMPING = 0.8f
        private const val RAW_MIN_ZOOM = 0.25f
        private const val RAW_MAX_ZOOM = 16f
        private const val ZOOM_BAND = 0.5f
        private const val EPS = 1e-4f
        private const val MIN_EXTENT = 0.01f
        private const val DECELERATION = 0.998f

        /** Fling landing distance (px) for a release velocity in px/s. */
        fun projection(v: Float): Float = (v / 1000f) * DECELERATION / (1f - DECELERATION)
    }
}
