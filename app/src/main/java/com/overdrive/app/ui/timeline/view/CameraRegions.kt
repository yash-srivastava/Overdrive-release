package com.overdrive.app.ui.timeline.view

import com.overdrive.app.ui.timeline.ClipSpec
import com.overdrive.app.ui.view.PlaybackDewarp
import com.overdrive.app.ui.view.ZoomableVideoView.Layout
import com.overdrive.app.ui.view.ZoomableVideoView.Quadrant
import kotlin.math.max
import kotlin.math.min

/** Frame composition of a clip. SINGLE: one camera fills the frame and counts as FRONT. */
enum class TileLayout {
    STANDARD,
    DASHCAM,
    SINGLE;

    companion object {
        fun of(spec: ClipSpec?): TileLayout = when {
            spec == null -> STANDARD
            spec.single -> SINGLE
            spec.lens != null -> STANDARD
            spec.layout == Layout.DASHCAM -> DASHCAM
            else -> STANDARD
        }
    }
}

/** Normalized sub-rectangle (x, y, w, h in 0..1, y down) of the source frame. */
data class Region(val x: Float, val y: Float, val w: Float, val h: Float)

/** A camera's visible rectangle in view px. */
data class CameraTile(val camera: Quadrant, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Where the region lands in the view: its top-left corner and full extent, in view px. */
class Placement {
    var left = 0f
    var top = 0f
    var width = 0f
    var height = 0f
}

/** Camera geometry shared by drawing and hit-testing; numbers match ZoomableVideoView. */
object CameraRegions {
    const val DASHCAM_SPLIT = 0.70f
    const val DASHCAM_BOTTOM = 0.30f
    const val DASHCAM_THIRD = 1f / 3f

    val FULL = Region(0f, 0f, 1f, 1f)

    private val FOUR = listOf(Quadrant.FRONT, Quadrant.RIGHT, Quadrant.REAR, Quadrant.LEFT)
    private val ONE = listOf(Quadrant.FRONT)

    fun regionFor(layout: TileLayout, q: Quadrant): Region = when (layout) {
        TileLayout.SINGLE -> FULL
        TileLayout.STANDARD -> when (q) {
            Quadrant.ALL -> FULL
            Quadrant.FRONT -> Region(0f, 0f, 0.5f, 0.5f)
            Quadrant.RIGHT -> Region(0.5f, 0f, 0.5f, 0.5f)
            Quadrant.REAR -> Region(0f, 0.5f, 0.5f, 0.5f)
            Quadrant.LEFT -> Region(0.5f, 0.5f, 0.5f, 0.5f)
        }
        TileLayout.DASHCAM -> when (q) {
            Quadrant.ALL -> FULL
            Quadrant.FRONT -> Region(0f, 0f, 1f, DASHCAM_SPLIT)
            Quadrant.LEFT -> Region(0f, DASHCAM_SPLIT, DASHCAM_THIRD, DASHCAM_BOTTOM)
            Quadrant.REAR -> Region(DASHCAM_THIRD, DASHCAM_SPLIT, DASHCAM_THIRD, DASHCAM_BOTTOM)
            Quadrant.RIGHT -> Region(2f * DASHCAM_THIRD, DASHCAM_SPLIT, DASHCAM_THIRD, DASHCAM_BOTTOM)
        }
    }

    fun cameras(layout: TileLayout): List<Quadrant> = if (layout == TileLayout.SINGLE) ONE else FOUR

    /** [q] when [layout] has that camera, otherwise ALL. */
    fun normalize(layout: TileLayout, q: Quadrant): Quadrant =
        if (layout == TileLayout.SINGLE && q != Quadrant.ALL && q != Quadrant.FRONT) Quadrant.ALL else q

    /** Camera under a frame-normalized point. */
    fun cameraAtFrame(layout: TileLayout, fx: Float, fy: Float): Quadrant = when (layout) {
        TileLayout.SINGLE -> Quadrant.FRONT
        TileLayout.DASHCAM ->
            if (fy < DASHCAM_SPLIT) {
                Quadrant.FRONT
            } else {
                when {
                    fx < DASHCAM_THIRD -> Quadrant.LEFT
                    fx < 2f * DASHCAM_THIRD -> Quadrant.REAR
                    else -> Quadrant.RIGHT
                }
            }
        TileLayout.STANDARD -> {
            val left = fx < 0.5f
            val top = fy < 0.5f
            when {
                top && left -> Quadrant.FRONT
                top && !left -> Quadrant.RIGHT
                !top && left -> Quadrant.REAR
                else -> Quadrant.LEFT
            }
        }
    }

    /** Size of [r] aspect-fitted in the view into out[0..1]; the whole view while the video size is unknown. */
    fun fitSize(viewW: Float, viewH: Float, videoW: Int, videoH: Int, r: Region, out: FloatArray) {
        var cw = viewW
        var ch = viewH
        if (videoW > 0 && videoH > 0 && r.w > 0f && r.h > 0f && viewW > 0f && viewH > 0f) {
            val aspect = (r.w * videoW) / (r.h * videoH)
            if (aspect < viewW / viewH) cw = viewH * aspect else ch = viewW / aspect
        }
        out[0] = cw
        out[1] = ch
    }

    /** Places a (fitW x fitH) region at [zoom] with its region-normalized point (cx, cy) on the view centre. */
    fun place(viewW: Float, viewH: Float, fitW: Float, fitH: Float, zoom: Float, cx: Float, cy: Float, out: Placement) {
        out.width = fitW * zoom
        out.height = fitH * zoom
        out.left = viewW / 2f - cx * out.width
        out.top = viewH / 2f - cy * out.height
    }

    /** Camera under view point (x, y); points on the letterbox resolve to the nearest camera. */
    fun cameraAt(layout: TileLayout, r: Region, p: Placement, x: Float, y: Float): Quadrant {
        if (p.width <= 0f || p.height <= 0f) return Quadrant.ALL
        val u = ((x - p.left) / p.width).coerceIn(0f, 1f)
        val v = ((y - p.top) / p.height).coerceIn(0f, 1f)
        return cameraAtFrame(layout, r.x + r.w * u, r.y + r.h * v)
    }

    /** Visible part of the placed region: view px (x0, y0, x1, y1), then frame-normalized (u0, v0, u1, v1). */
    fun visibleQuad(viewW: Float, viewH: Float, r: Region, p: Placement, out: FloatArray): Boolean {
        if (p.width <= 0f || p.height <= 0f) return false
        val x0 = max(0f, p.left)
        val y0 = max(0f, p.top)
        val x1 = min(viewW, p.left + p.width)
        val y1 = min(viewH, p.top + p.height)
        if (x1 <= x0 || y1 <= y0) return false
        out[0] = x0
        out[1] = y0
        out[2] = x1
        out[3] = y1
        out[4] = r.x + r.w * ((x0 - p.left) / p.width)
        out[5] = r.y + r.h * ((y0 - p.top) / p.height)
        out[6] = r.x + r.w * ((x1 - p.left) / p.width)
        out[7] = r.y + r.h * ((y1 - p.top) / p.height)
        return true
    }

    /** Lowest allowed region-normalized centre on an axis; the content stays centred while it fits. */
    fun centerMin(extent: Float, viewSize: Float): Float =
        if (extent <= viewSize || extent <= 0f) 0.5f else viewSize / (2f * extent)

    /** On-screen rectangles of the cameras visible through the placed region. */
    fun tiles(layout: TileLayout, viewW: Float, viewH: Float, r: Region, p: Placement): List<CameraTile> {
        if (p.width <= 0f || p.height <= 0f || r.w <= 0f || r.h <= 0f) return emptyList()
        val vx0 = max(0f, p.left)
        val vy0 = max(0f, p.top)
        val vx1 = min(viewW, p.left + p.width)
        val vy1 = min(viewH, p.top + p.height)
        val out = ArrayList<CameraTile>(4)
        for (q in cameras(layout)) {
            val c = regionFor(layout, q)
            val l = max(vx0, p.left + (c.x - r.x) / r.w * p.width)
            val t = max(vy0, p.top + (c.y - r.y) / r.h * p.height)
            val rr = min(vx1, p.left + (c.x + c.w - r.x) / r.w * p.width)
            val b = min(vy1, p.top + (c.y + c.h - r.y) / r.h * p.height)
            if (rr - l >= 1f && b - t >= 1f) out.add(CameraTile(q, l, t, rr, b))
        }
        return out
    }

    /** Live (k1, k2) into out: the slider maps exactly as PlaybackDewarp.specFor; strength < 0 keeps the spec's own. */
    fun lensK(lens: PlaybackDewarp.Spec, strength: Int, out: FloatArray) {
        if (strength < 0) {
            out[0] = lens.k1
            out[1] = lens.k2
            return
        }
        val t = strength.coerceIn(0, 100) / 100f
        out[0] = 0.30f * t
        out[1] = 0.10f * t
    }

    /** Lens tag geometry PlaybackDewarp accepts. */
    fun lensUsable(lens: PlaybackDewarp.Spec): Boolean =
        lens.frameWidth in 2..8192 && lens.frameHeight in 2..8192 && lens.aspect in 0.25f..1.0f
}
