package com.overdrive.app.navmap.motion

import kotlin.math.exp
import kotlin.math.min

/**
 * Heading-up follow camera. The puck sits at a fixed screen point (via top padding) and the camera
 * centres on it, so the car stays put while the map moves under it. Zoom, tilt and that anchor
 * point follow speed and the distance to the next maneuver through critically damped springs.
 */
class NavCameraRig {

    enum class Mode { FOLLOW, FREE, OVERVIEW }

    /** One camera pose: centre, zoom, tilt, bearing and top padding (px). */
    class Pose {
        var lat = 0.0
        var lng = 0.0
        var zoom = 0.0
        var tilt = 0.0
        var bearing = 0.0
        var paddingTop = 0.0

        fun set(o: Pose): Pose {
            lat = o.lat; lng = o.lng; zoom = o.zoom; tilt = o.tilt; bearing = o.bearing; paddingTop = o.paddingTop
            return this
        }
    }

    var mode = Mode.FOLLOW
        private set
    /** True after [reset] until a mode is chosen: the first follow frame should ease in from the current camera. */
    var needsEntry = true
        private set
    /** Follow with north at the top instead of heading-up; the puck still turns. */
    var northUp = false
        private set

    private val zoom = FollowSpring(ZOOM_OMEGA)
    private val tilt = FollowSpring(TILT_OMEGA)
    private val anchor = FollowSpring(ANCHOR_OMEGA)
    private var speedLp = Double.NaN
    private var lastNowMs = 0L
    private var googleBand = -1

    private var transitionFrom: Pose? = null
    private var transitionStartMs = 0L
    private var transitionMs = 0L
    private val overviewPose = Pose()
    private var freeSinceMs = 0L

    private val follow = Pose()
    private val out = Pose()

    /** Back to the follow camera, easing from [current] over [durationMs]. */
    fun follow(nowMs: Long, current: Pose?, durationMs: Long) {
        mode = Mode.FOLLOW
        needsEntry = false
        startTransition(nowMs, current, durationMs)
    }

    /** The driver moved the map: stop driving the camera until [recenterAfterMs] of no touch. */
    fun free(nowMs: Long) {
        mode = Mode.FREE
        needsEntry = false
        freeSinceMs = nowMs
        transitionFrom = null
    }

    fun overview(nowMs: Long, current: Pose?, target: Pose, durationMs: Long) {
        mode = Mode.OVERVIEW
        needsEntry = false
        overviewPose.set(target)
        startTransition(nowMs, current, durationMs)
    }

    /** Switches heading-up ↔ north-up, easing from [current]. */
    fun setNorthUp(nowMs: Long, current: Pose?, value: Boolean, durationMs: Long) {
        if (northUp == value) return
        northUp = value
        if (mode == Mode.FOLLOW) startTransition(nowMs, current, durationMs)
    }

    fun reset() {
        mode = Mode.FOLLOW
        needsEntry = true
        zoom.clear(); tilt.clear(); anchor.clear()
        speedLp = Double.NaN
        googleBand = -1
        transitionFrom = null
    }

    val transitioning: Boolean get() = transitionFrom != null

    /** 0 → 1 through the FREE idle window before the automatic recentre; 0 when not FREE. */
    fun freeProgress(nowMs: Long): Float =
        if (mode != Mode.FREE) 0f else ((nowMs - freeSinceMs).toFloat() / RECENTER_AFTER_MS).coerceIn(0f, 1f)

    /** True once a FREE camera has gone untouched long enough to recentre by itself. */
    fun freeExpired(nowMs: Long): Boolean = mode == Mode.FREE && nowMs - freeSinceMs >= RECENTER_AFTER_MS

    /**
     * Camera for this frame, or null when the driver owns it (FREE). [distToTurnM]/[sinceTurnM] are
     * along-route distances to the next and from the last framed maneuver (∞ when unknown).
     */
    fun update(
        nowMs: Long,
        puckLat: Double, puckLng: Double, bearing: Double, speedMps: Double,
        distToTurnM: Double, sinceTurnM: Double,
        viewHeightPx: Int, rasterBands: Boolean, cluster: Boolean
    ): Pose? {
        val dt = if (lastNowMs == 0L) 0.0 else ((nowMs - lastNowMs).coerceAtLeast(0L)) / 1000.0
        lastNowMs = nowMs
        if (mode == Mode.FREE) return null
        val kmh = speedLp(speedMps, dt) * 3.6

        val z = zoom.step(if (rasterBands) bandZoom(kmh) else targetZoom(kmh, distToTurnM, sinceTurnM), dt)
        val tl = tilt.step(GUIDANCE_TILT + (TILT_MAX - GUIDANCE_TILT) * unit((kmh - 20.0) / 80.0), dt)
        val base = if (cluster) ANCHOR_CLUSTER else ANCHOR_HEAD_UNIT
        val f = anchor.step(base + ANCHOR_SPEED_GAIN * unit(kmh / 100.0), dt)
        follow.lat = puckLat
        follow.lng = puckLng
        follow.zoom = z
        follow.tilt = tl
        follow.bearing = if (northUp) 0.0 else bearing
        follow.paddingTop = (2.0 * f - 1.0) * viewHeightPx

        val target = if (mode == Mode.OVERVIEW) overviewPose else follow
        val from = transitionFrom ?: return out.set(target)
        val u = ((nowMs - transitionStartMs).toDouble() / transitionMs).coerceIn(0.0, 1.0)
        if (u >= 1.0) {
            transitionFrom = null
            return out.set(target)
        }
        val e = easeInOut(u)
        out.lat = from.lat + (target.lat - from.lat) * e
        out.lng = from.lng + (target.lng - from.lng) * e
        out.zoom = from.zoom + (target.zoom - from.zoom) * e
        out.tilt = from.tilt + (target.tilt - from.tilt) * e
        out.bearing = AngleSpring.norm(from.bearing + AngleSpring.shortestArc(from.bearing, target.bearing) * e)
        out.paddingTop = from.paddingTop + (target.paddingTop - from.paddingTop) * e
        return out
    }

    private fun startTransition(nowMs: Long, current: Pose?, durationMs: Long) {
        if (current == null || durationMs <= 0L) {
            transitionFrom = null
            return
        }
        transitionFrom = Pose().set(current)
        transitionStartMs = nowMs
        transitionMs = durationMs
        // Start the springs where the camera is, so zoom/tilt don't snap when the ease ends.
        zoom.snap(current.zoom)
        tilt.snap(current.tilt)
    }

    private fun speedLp(speedMps: Double, dt: Double): Double {
        speedLp = if (speedLp.isNaN()) speedMps else speedLp + (speedMps - speedLp) * (1.0 - exp(-dt / SPEED_TAU_S))
        return speedLp
    }

    private fun targetZoom(kmh: Double, distToTurnM: Double, sinceTurnM: Double): Double {
        val base = interp(kmh, ZOOM_KMH, ZOOM_LEVELS)
        val highway = kmh >= HIGHWAY_KMH
        val reach = if (highway) APPROACH_HIGHWAY_M else APPROACH_CITY_M
        val junction = if (highway) JUNCTION_ZOOM_HIGHWAY else JUNCTION_ZOOM_CITY
        if (junction <= base) return base
        val w = when {
            sinceTurnM <= HOLD_AFTER_TURN_M -> 1.0
            distToTurnM < reach -> smoothstep(1.0 - distToTurnM / reach)
            else -> 0.0
        }
        return base + (junction - base) * w
    }

    // Raster tiles change level at x.5, so the Google basemap holds one level per band.
    private fun bandZoom(kmh: Double): Double {
        val b = googleBand
        val next = when {
            b < 0 -> bandOf(kmh)
            kmh > BAND_KMH.getOrElse(b) { Double.MAX_VALUE } + BAND_HYST_KMH -> min(b + 1, BAND_ZOOM.size - 1)
            b > 0 && kmh < BAND_KMH[b - 1] - BAND_HYST_KMH -> b - 1
            else -> b
        }
        googleBand = next
        return BAND_ZOOM[next]
    }

    private fun bandOf(kmh: Double): Int {
        for (i in BAND_KMH.indices) if (kmh < BAND_KMH[i]) return i
        return BAND_ZOOM.size - 1
    }

    companion object {
        private const val ZOOM_OMEGA = 1.1
        private const val TILT_OMEGA = 1.0
        private const val ANCHOR_OMEGA = 1.0
        private const val SPEED_TAU_S = 2.0
        const val RECENTER_AFTER_MS = 12_000L

        private val ZOOM_KMH = doubleArrayOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0, 130.0)
        private val ZOOM_LEVELS = doubleArrayOf(17.8, 17.6, 17.1, 16.6, 16.15, 15.75, 15.3)
        private const val HIGHWAY_KMH = 70.0
        private const val APPROACH_CITY_M = 260.0
        private const val APPROACH_HIGHWAY_M = 520.0
        private const val JUNCTION_ZOOM_CITY = 17.4
        private const val JUNCTION_ZOOM_HIGHWAY = 16.9
        private const val HOLD_AFTER_TURN_M = 35.0

        // Upper edge (km/h) of each band; the last band is open-ended.
        private val BAND_KMH = doubleArrayOf(25.0, 55.0, 90.0)
        private val BAND_ZOOM = doubleArrayOf(17.4, 17.0, 16.0, 15.0)
        private const val BAND_HYST_KMH = 4.0

        const val GUIDANCE_TILT = 55.0
        const val RESTING_TILT = GUIDANCE_TILT * 0.62
        private const val TILT_MAX = 57.0
        // Puck height as a fraction of the view from the top; lower at speed to show more road ahead.
        private const val ANCHOR_HEAD_UNIT = 0.66
        private const val ANCHOR_CLUSTER = 0.68
        private const val ANCHOR_SPEED_GAIN = 0.08

        private fun unit(x: Double) = x.coerceIn(0.0, 1.0)
        private fun smoothstep(x: Double): Double { val t = unit(x); return t * t * (3 - 2 * t) }
        private fun easeInOut(u: Double): Double = if (u < 0.5) 4 * u * u * u else 1 - Math.pow(-2 * u + 2, 3.0) / 2

        private fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
            if (x <= xs[0]) return ys[0]
            for (i in 1 until xs.size) {
                if (x <= xs[i]) {
                    val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
                    return ys[i - 1] + (ys[i] - ys[i - 1]) * t
                }
            }
            return ys[ys.size - 1]
        }
    }
}
