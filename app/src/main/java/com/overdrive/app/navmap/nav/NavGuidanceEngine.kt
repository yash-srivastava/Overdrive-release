package com.overdrive.app.navmap.nav

import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The turn-by-turn guidance state machine — our hand-rolled replacement for
 * ferrostar's core (rejected: Kotlin 2.3.0 / desugaring / JNA-Rust .so vs this
 * project's Kotlin 2.0.21).
 *
 * <p>It holds an active [NavRoute] and is fed raw GPS fixes by the Activity via
 * [update]. On each fix it snaps the position to the route, detects off-route,
 * resolves the current/next maneuver, and computes remaining distance + ETA and
 * arrival. It is PURE logic (only `android.util.Log` from the framework),
 * deterministic, and unit-testable.
 *
 * <p>Units: latitude/longitude are WGS-84 decimal degrees; all distances are
 * meters; all durations/ETAs are seconds. Threading: instances are NOT
 * thread-safe — call [start]/[update]/[stop] from a single thread (the
 * location-callback thread).
 */
class NavGuidanceEngine {

    companion object {
        private const val TAG = "NavGuidanceEngine"

        /** Earth mean radius in meters (for the haversine helper). */
        private const val EARTH_RADIUS_M = 6_371_000.0

        /**
         * Lateral distance from the route (meters) beyond which a fix counts as
         * a candidate off-route sample.
         */
        const val OFF_ROUTE_THRESHOLD_M = 50.0

        /**
         * Number of consecutive off-route-candidate fixes required before
         * [GuidanceState.offRoute] latches true (debounce against GPS jitter).
         */
        const val OFF_ROUTE_CONSECUTIVE = 3

        /**
         * Distance (meters) from the destination within which arrival is
         * declared. Sized for head-unit GPS (which can sit 20-30 m off the true
         * position): too tight and the car parks "near" the pin without ever
         * tripping arrival, so guidance keeps hunting a reroute around the last
         * block. The Activity adds a speed-gated near-stop arrival on top.
         */
        const val ARRIVAL_RADIUS_M = 35.0

        /** Lateral off-route distance (m) for the adaptive latch — tighter than the
         *  legacy 50m because the heading gate + time budget now guard false trips. */
        private const val OFF_ROUTE_LATERAL_M = 38.0
        /** Heading divergence (deg) from the route tangent that counts as "wrong way". */
        private const val OFF_ROUTE_HEADING_DEG = 45.0
        /** Min lateral distance (m) before the heading gate engages (avoids tripping
         *  on a normal lane offset while still pointed down the road). */
        private const val HEADING_GATE_MIN_LATERAL_M = 14.0
        /** Off-route confirm time budgets (ms), by severity of the divergence. */
        private const val OFF_ROUTE_MS_WRONG_WAY = 1500L   // far + pointed away → fast
        private const val OFF_ROUTE_MS_DIVERGING = 2000L   // far, heading unknown
        private const val OFF_ROUTE_MS_DEFAULT = 3000L     // city default
        private const val OFF_ROUTE_MS_HIGHWAY = 6000L     // ≥80 km/h → patient (ramps)
        private const val HIGHWAY_SPEED_MPS = 22.2         // ~80 km/h
        /** Forward search window (segments) for the monotonic matcher. */
        private const val MATCH_FWD_WINDOW = 16
        /** Allowed backward search (segments) — small, to recover from a bad fix
         *  without letting the match jump back on a parallel/return leg. */
        private const val MATCH_BACK_WINDOW = 3
        /** A first fix matches the earliest segment within this much of the nearest. */
        private const val FIRST_MATCH_SLACK_M = 25.0
        /** Under way once this much route (or this share of a short one) is behind the car. */
        private const val SET_OUT_M = 150.0
        private const val SET_OUT_FRACTION = 0.3
        /** A reroute to within this of the old destination is the same trip (routers snap the end differently). */
        private const val SAME_DEST_M = 150.0
        /** Fixes at this speed or more add to the distance driven. */
        private const val MOVING_MPS = 2.0
        private const val JITTER_MAX_M = 15.0
        private const val STOPPED_MPS = 1.0
        /** Along-route reach between fixes beyond twice the distance moved. */
        private const val ADVANCE_SLACK_M = 50.0
        /** Near the end, a car this near the route is wandering GPS, not off it. */
        private const val NEAR_DEST_WANDER_M = 60.0
        /** A pass this much further from a point than the route's closest still counts as an approach to it. */
        private const val APPROACH_SLACK_M = 5.0

        /**
         * Remaining along-route distance (m) within which a reroute is pointless:
         * the driver is on the final approach, so a GPS wander that looks like an
         * off-route excursion must NOT trigger a recompute (it would route the car
         * in a loop around its own destination). Off-route latching is suppressed
         * inside this band — guidance just waits for arrival. Sized to cover the
         * whole "final 50-100 m" approach where a recompute is never useful.
         */
        const val NEAR_DEST_NO_REROUTE_M = 90.0
    }

    private var route: NavRoute? = null

    /** Prefix-sum of haversine arc length (m) along [route].points: cumArcM[i] is the
     *  distance from the route start to vertex i. Built once in [start]. Drives the
     *  along-route progress fraction for the traveled-route trim (line-gradient) — that
     *  fraction MUST be measured against the POLYLINE arc length (not the Valhalla
     *  summary [NavRoute.totalDistanceMeters], which differs from the densified
     *  polyline length and would drift the trim boundary off the puck). */
    private var cumArcM: DoubleArray = DoubleArray(0)
    /** Total polyline arc length (m) = cumArcM.last(); 0.0 when no route. */
    private var routeArcLenM: Double = 0.0

    /** Count of consecutive fixes whose route distance exceeded the threshold
     *  (legacy count-based latch, used by the no-bearing update overload). */
    private var consecutiveOffRoute = 0

    /** Carried matched-segment index for the windowed monotonic matcher (−1 = none). */
    private var matchedSegIndex = -1
    /** elapsedRealtime ms when the current off-route divergence began (0 = on route), less the time stood still since. */
    private var offRouteSinceMs = 0L
    // the last timed update, and whether the car stood still then
    private var lastUpdateMs = 0L
    private var lastStood = false

    /**
     * Begin guidance along [route]. Resets all per-route latch + matcher state.
     */
    /** Part of a route to this destination is behind the car (reroutes to the same destination keep it). */
    var hasSetOut = false
        private set
    private var destLat = Double.NaN
    private var destLng = Double.NaN
    /** Distance driven (moving fixes only) since guidance to this destination began. */
    var traveledM = 0.0
        private set
    /** Along-route position of the last match (−1: none yet, this route). */
    private var lastAlongM = -1.0
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    // the last match on the route itself
    private var goodAlongM = -1.0
    // how far off the route the last fix was
    private var lastOffM = Double.NaN

    private fun alongOf(seg: Int, t: Double): Double =
        if (seg + 1 < cumArcM.size) cumArcM[seg] + t * (cumArcM[seg + 1] - cumArcM[seg]) else routeArcLenM

    fun start(route: NavRoute) {
        this.route = route
        this.consecutiveOffRoute = 0
        this.matchedSegIndex = -1
        this.offRouteSinceMs = 0L
        lastUpdateMs = 0L
        lastStood = false
        val end = route.points.lastOrNull()
        if (end == null || destLat.isNaN() || haversineMeters(destLat, destLng, end.lat, end.lng) > SAME_DEST_M) {
            hasSetOut = false
            traveledM = 0.0
        }
        lastAlongM = -1.0
        goodAlongM = -1.0
        lastOffM = Double.NaN
        destLat = end?.lat ?: Double.NaN
        destLng = end?.lng ?: Double.NaN
        // Prefix-sum the polyline arc length for the progress-fraction (route-trim) feed.
        val pts = route.points
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] + haversineMeters(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng)
        }
        cumArcM = cum
        routeArcLenM = if (cum.isNotEmpty()) cum[cum.size - 1] else 0.0
        Log.i(TAG, "guidance started: ${route.points.size} pts, ${route.maneuvers.size} maneuvers")
    }

    /** Stop guidance and clear all state. Subsequent [update] calls no-op. */
    fun stop() {
        this.route = null
        this.consecutiveOffRoute = 0
        this.matchedSegIndex = -1
        this.offRouteSinceMs = 0L
        lastUpdateMs = 0L
        lastStood = false
        this.cumArcM = DoubleArray(0)
        this.routeArcLenM = 0.0
        hasSetOut = false
        traveledM = 0.0
        lastAlongM = -1.0
        goodAlongM = -1.0
        lastOffM = Double.NaN
        lastLat = Double.NaN
        lastLng = Double.NaN
        destLat = Double.NaN
        destLng = Double.NaN
        Log.i(TAG, "guidance stopped")
    }

    /** Total polyline arc length (m) of the active route, or 0.0 when none. The
     *  denominator for the traveled-route progress fraction (see [cumArcM]). */
    fun routeArcLengthMeters(): Double = routeArcLenM

    fun activeRoute(): NavRoute? = route

    /** Whether a route is currently loaded (between [start] and [stop]). */
    fun isActive(): Boolean = route != null

    /** Along-route metres of the last match on the route itself (−1: none yet on this route). */
    val progressM: Double get() = goodAlongM

    /** The last fix was on the route (within its lateral band), whatever the off-route latch says yet. */
    val onRoute: Boolean get() = lastOffM <= OFF_ROUTE_LATERAL_M

    /** How far off the route the last fix was (NaN: none yet). */
    val offRouteM: Double get() = lastOffM

    /** A trip picked up again after a restart had already set out: the latch carries over. */
    fun resumeSetOut() {
        if (route != null) hasSetOut = true
    }

    class RoutePoint(val alongM: Double, val lat: Double, val lng: Double)

    /**
     * The route's first close approach to (lat, lng) at or beyond [fromAlongM]: the earliest point within a few metres
     * of the closest there, so a road the route uses twice counts at the pass that comes first. Null with no route.
     */
    fun approachAfter(lat: Double, lng: Double, fromAlongM: Double): RoutePoint? {
        val pts = route?.points ?: return null
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            if (cumArcM[i + 1] < fromAlongM) continue
            val s = pointToSegment(lat, lng, pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
            if (alongOf(i, s.t) >= fromAlongM && s.distMeters < best) best = s.distMeters
        }
        if (best == Double.MAX_VALUE) return null
        for (i in 0 until pts.size - 1) {
            if (cumArcM[i + 1] < fromAlongM) continue
            val s = pointToSegment(lat, lng, pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
            val a = alongOf(i, s.t)
            if (a >= fromAlongM && s.distMeters <= best + APPROACH_SLACK_M) return RoutePoint(a, s.lat, s.lng)
        }
        return null
    }

    /**
     * Process one GPS fix and return the derived [GuidanceState].
     *
     * <p>If no route is active (no [start] yet, or after [stop]) this returns a
     * neutral state echoing the input position with no maneuver. Otherwise it:
     * snaps the fix to the nearest route segment; updates the off-route latch;
     * resolves the next maneuver and the distance to it; and computes remaining
     * distance, ETA and arrival.
     *
     * @param lat fix latitude in decimal degrees
     * @param lng fix longitude in decimal degrees
     * @return the guidance state for this fix
     */
    fun update(lat: Double, lng: Double): GuidanceState =
        update(lat, lng, bearingDeg = null, speedMps = 0.0, nowMs = 0L)

    /**
     * Richer update: same as [update] but with the fix's heading + speed + a
     * monotonic clock, enabling the windowed map-matcher (forward-biased snap that
     * resists back-snapping on self-crossing/parallel routes) and the adaptive
     * off-route latch (a speed/heading-scaled time budget instead of a fixed
     * consecutive-count). Pass nowMs=0 to use the legacy count-based latch
     * (the no-arg [update] does this — keeps existing callers/tests unchanged).
     */
    fun update(lat: Double, lng: Double, bearingDeg: Double?, speedMps: Double, nowMs: Long): GuidanceState {
        val r = route ?: return GuidanceState(
            snappedLat = lat,
            snappedLng = lng,
            offRoute = false,
            arrived = false,
            currentManeuver = null,
            distanceToManeuverM = 0.0,
            remainingDistanceM = 0.0,
            etaSeconds = 0.0
        )

        val pts = r.points
        if (pts.size < 2) {
            // Degenerate route — fall back to point-distance arrival on the
            // single vertex (if any).
            val arrived = pts.isNotEmpty() &&
                haversineMeters(lat, lng, pts[0].lat, pts[0].lng) <= ARRIVAL_RADIUS_M
            return GuidanceState(lat, lng, false, arrived, null, 0.0, 0.0, 0.0)
        }

        // 1) Snap. Windowed monotonic match when we have a carried index: search a
        //    forward window (+small back window) around it so the snap advances along
        //    the route and doesn't jump to a nearer-but-earlier/parallel segment.
        //    First fix (no carried index) falls back to a full global scan.
        // The car can't advance along the route faster than it moves: a candidate further on than that (a loop's own
        // end, a later leg passing close by) is a jump, not progress.
        val moved = if (lastLat.isNaN()) 0.0 else haversineMeters(lastLat, lastLng, lat, lng)
        lastLat = lat
        lastLng = lng
        // (a move too big for GPS jitter counts even with no speed reading)
        if (speedMps >= MOVING_MPS || moved >= JITTER_MAX_M) traveledM += moved
        val maxAlong = if (lastAlongM < 0.0) Double.MAX_VALUE else lastAlongM + 2.0 * moved + ADVANCE_SLACK_M
        var bestDist = Double.MAX_VALUE
        var bestSegStart = 0
        var bestT = 0.0
        var bestLat = pts[0].lat
        var bestLng = pts[0].lng
        val lo: Int
        val hi: Int
        if (matchedSegIndex in 0 until pts.size - 1) {
            lo = (matchedSegIndex - MATCH_BACK_WINDOW).coerceAtLeast(0)
            hi = (matchedSegIndex + MATCH_FWD_WINDOW).coerceAtMost(pts.size - 2)
        } else { lo = 0; hi = pts.size - 2 }
        for (pass in 0..1) {
            // the second pass only if nothing in reach was found (a long gap between fixes, a teleport)
            if (pass == 1 && bestDist < Double.MAX_VALUE) break
            for (i in lo..hi) {
                val a = pts[i]; val b = pts[i + 1]
                val snap = pointToSegment(lat, lng, a.lat, a.lng, b.lat, b.lng)
                if (pass == 0 && alongOf(i, snap.t) > maxAlong) continue
                if (snap.distMeters < bestDist) {
                    bestDist = snap.distMeters; bestSegStart = i; bestT = snap.t
                    bestLat = snap.lat; bestLng = snap.lng
                }
            }
        }
        // First fix: of the segments about as near, the earliest. A round trip starts on its own last leg's street,
        // and matching there would put the car at the end.
        if (matchedSegIndex < 0) {
            for (i in 0 until bestSegStart) {
                val a = pts[i]; val b = pts[i + 1]
                val snap = pointToSegment(lat, lng, a.lat, a.lng, b.lat, b.lng)
                if (snap.distMeters <= bestDist + FIRST_MATCH_SLACK_M) {
                    bestDist = snap.distMeters; bestSegStart = i; bestT = snap.t
                    bestLat = snap.lat; bestLng = snap.lng
                    break
                }
            }
        }
        // If the windowed match is poor (we likely diverged or the window was wrong),
        // do ONE global rescan so a real reroute/teleport re-acquires correctly — within reach along the route.
        if (matchedSegIndex >= 0 && bestDist > OFF_ROUTE_LATERAL_M) {
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                val snap = pointToSegment(lat, lng, a.lat, a.lng, b.lat, b.lng)
                if (alongOf(i, snap.t) > maxAlong) continue
                if (snap.distMeters < bestDist) {
                    bestDist = snap.distMeters; bestSegStart = i; bestT = snap.t
                    bestLat = snap.lat; bestLng = snap.lng
                }
            }
        }
        lastAlongM = alongOf(bestSegStart, bestT)
        matchedSegIndex = bestSegStart
        if (bestDist <= OFF_ROUTE_LATERAL_M) goodAlongM = lastAlongM
        lastOffM = bestDist

        // 2) Off-route detection.
        var offRoute: Boolean
        if (nowMs <= 0L) {
            // Legacy count-based latch (no bearing supplied).
            if (bestDist > OFF_ROUTE_THRESHOLD_M) consecutiveOffRoute++ else consecutiveOffRoute = 0
            offRoute = consecutiveOffRoute >= OFF_ROUTE_CONSECUTIVE
        } else {
            // Adaptive latch: lateral distance AND (when far enough) a heading that
            // diverges from the route tangent, confirmed over a speed-scaled time
            // budget. Wrong-way trips fast; highway is patient (ramps run parallel).
            val routeBearing = bearingBetween(
                pts[bestSegStart].lat, pts[bestSegStart].lng,
                pts[bestSegStart + 1].lat, pts[bestSegStart + 1].lng
            )
            val headingDiverged = bearingDeg != null && bestDist > HEADING_GATE_MIN_LATERAL_M &&
                kotlin.math.abs(shortestArcDelta(routeBearing, bearingDeg)) > OFF_ROUTE_HEADING_DEG
            val diverging = bestDist > OFF_ROUTE_LATERAL_M
            if (diverging || headingDiverged) {
                if (offRouteSinceMs == 0L) offRouteSinceMs = nowMs
                // (time stood still neither counts nor starts it over: off the route in stop-and-go still leaves it)
                else if ((speedMps < STOPPED_MPS || lastStood) && lastUpdateMs in 1..nowMs) {
                    offRouteSinceMs = minOf(nowMs, offRouteSinceMs + (nowMs - lastUpdateMs))
                }
                val budget = when {
                    headingDiverged && diverging -> OFF_ROUTE_MS_WRONG_WAY
                    speedMps >= HIGHWAY_SPEED_MPS -> OFF_ROUTE_MS_HIGHWAY
                    headingDiverged -> OFF_ROUTE_MS_DIVERGING
                    else -> OFF_ROUTE_MS_DEFAULT
                }
                offRoute = (nowMs - offRouteSinceMs) >= budget
            } else {
                offRouteSinceMs = 0L
                offRoute = false
            }
            lastUpdateMs = nowMs
            lastStood = speedMps < STOPPED_MPS
        }

        // 3) Remaining distance: partial remainder of the current segment plus
        //    every subsequent full segment.
        val segLen = haversineMeters(
            pts[bestSegStart].lat, pts[bestSegStart].lng,
            pts[bestSegStart + 1].lat, pts[bestSegStart + 1].lng
        )
        var remaining = segLen * (1.0 - bestT)
        for (i in bestSegStart + 1 until pts.size - 1) {
            remaining += haversineMeters(pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
        }

        // A car standing still isn't leaving the route (GPS drifts under a canopy, at a pump).
        if (speedMps < STOPPED_MPS) {
            offRoute = false
            consecutiveOffRoute = 0
        }

        // 2b) Final-approach guard: once the along-route remainder is inside the
        //     near-destination band, suppress any off-route latch. A GPS wander
        //     this close to the pin must NOT spawn a reroute (it would loop the car
        //     around its own destination); guidance just waits for arrival.
        // (only for a car near the route: one well off it there missed the last turn and needs a reroute)
        if (remaining <= NEAR_DEST_NO_REROUTE_M && bestDist <= NEAR_DEST_WANDER_M) {
            offRoute = false
            offRouteSinceMs = 0L
            consecutiveOffRoute = 0
        }

        // 4) Arrival: within the radius of the final route vertex, OR the
        //    along-route remainder is effectively zero (snapped past the last
        //    vertex). The straight-line check handles a GPS fix that's laterally
        //    off the final segment; the remainder check handles overshooting it.
        //    Along the route only (so every stop on it passed in order, and a destination across a divided road isn't
        //    reached from the far side); the map adds a long stop at the pin itself. Nothing counts until part of the
        //    route is behind the car: a round trip starts at its own end, a loop round the block never gets far from it.
        //    Under way takes both: the route behind the car, and the car actually having driven that far (a jump
        //    along the route doesn't count).
        val setOutM = minOf(SET_OUT_M, routeArcLenM * SET_OUT_FRACTION)
        if (!hasSetOut && routeArcLenM - remaining >= setOutM && traveledM >= setOutM) hasSetOut = true
        // (on the route: the remainder to the end means nothing for a car that went on past the last turn)
        val arrived = hasSetOut && remaining <= ARRIVAL_RADIUS_M && bestDist <= OFF_ROUTE_LATERAL_M

        // 5) ETA: scale the route's total duration by the remaining fraction of
        //    its total distance (proportional model — robust when per-maneuver
        //    times are absent). Falls back to 0 when totals are missing.
        val etaSeconds = if (r.totalDistanceMeters > 0.0 && r.totalDurationSeconds > 0.0) {
            r.totalDurationSeconds * (remaining / r.totalDistanceMeters).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        // 6) Next maneuver: the first maneuver whose begin vertex is at or ahead
        //    of where we are. "Ahead" = its begin index is past the current
        //    segment, or on the current segment but ahead of our progress.
        val maneuver = nextManeuver(r, bestSegStart, bestT)
        val distanceToManeuver = if (maneuver != null) {
            distanceAlongTo(pts, bestSegStart, bestT, bestLat, bestLng, maneuver.beginShapeIndex)
        } else {
            0.0
        }
        // The "then …" preview maneuver — shown only once the current one is close
        // (the Activity gates display), so a far-off second turn doesn't clutter.
        val thenManeuver = maneuver?.let { maneuverAfter(r, it) }

        return GuidanceState(
            snappedLat = bestLat,
            snappedLng = bestLng,
            offRoute = offRoute,
            arrived = arrived,
            currentManeuver = maneuver,
            distanceToManeuverM = distanceToManeuver,
            remainingDistanceM = remaining,
            etaSeconds = etaSeconds,
            nextManeuver = thenManeuver
        )
    }

    /**
     * Snap an arbitrary point onto the active route for the per-FRAME render tick,
     * returning the on-route position plus the route's tangent bearing there.
     *
     * <p>The 1 Hz [update] already snaps the truth fix, but the ~12 fps render loop
     * dead-reckons forward BETWEEN fixes — left raw, that prediction drifts off the
     * polyline (the puck reads as floating beside the road, and the heading-up camera
     * chases noisy GPS heading). Snapping every frame keeps the puck glued to the line
     * AND gives a perfectly smooth heading (the segment tangent) to steer the camera by.
     *
     * <p>It reuses the carried [matchedSegIndex] window so it stays cheap and forward-
     * biased (no back-snapping on self-crossing routes). It does NOT mutate any latch
     * state — it's a pure read against the current route + match window. Returns null
     * when no route is active so the caller can fall back to the raw estimate.
     *
     * @param lat predicted latitude (decimal degrees)
     * @param lng predicted longitude (decimal degrees)
     * @return snapped position + tangent bearing, or null if no route / degenerate
     */
    fun snapToRoute(lat: Double, lng: Double): Snapped? {
        val r = route ?: return null
        val pts = r.points
        if (pts.size < 2) return null
        val lo: Int
        val hi: Int
        if (matchedSegIndex in 0 until pts.size - 1) {
            lo = (matchedSegIndex - MATCH_BACK_WINDOW).coerceAtLeast(0)
            hi = (matchedSegIndex + MATCH_FWD_WINDOW).coerceAtMost(pts.size - 2)
        } else { lo = 0; hi = pts.size - 2 }
        var bestDist = Double.MAX_VALUE
        var bestSeg = lo
        var bestLat = pts[lo].lat
        var bestLng = pts[lo].lng
        for (i in lo..hi) {
            val a = pts[i]; val b = pts[i + 1]
            val snap = pointToSegment(lat, lng, a.lat, a.lng, b.lat, b.lng)
            if (snap.distMeters < bestDist) {
                bestDist = snap.distMeters; bestSeg = i
                bestLat = snap.lat; bestLng = snap.lng
            }
        }
        // NOTE: this per-frame snap is deliberately WINDOWED-ONLY (v26.8 behaviour). An
        // added "global rescan when bestDist > OFF_ROUTE_LATERAL_M" was reverted: because
        // it is a pure read that does NOT advance matchedSegIndex, successive render frames
        // flipped the snap between the windowed match and the globally-nearest segment
        // (a parallel carriageway / return leg on a loop) → the puck teleported/jittered
        // off the line. The 1 Hz update() remains the SOLE owner of off-route recovery +
        // matchedSegIndex re-acquisition; the render-frame snap must stay inside that
        // window so it can't disagree with the latch.
        // Tangent bearing along the matched segment — smooth by construction
        // (it only changes at vertices), so the camera never chases GPS noise.
        val tangent = bearingBetween(
            pts[bestSeg].lat, pts[bestSeg].lng,
            pts[bestSeg + 1].lat, pts[bestSeg + 1].lng
        )
        // Along-route arc distance to the snap point = (prefix sum to the segment start)
        // + (partial from the segment start vertex to the snapped point). Used by the
        // traveled-route trim's progress fraction. Guard cumArcM (may lag the route by a
        // frame on a just-started/just-rerouted engine) → fall back to 0 (no trim).
        val alongRouteM = if (bestSeg < cumArcM.size) {
            cumArcM[bestSeg] + haversineMeters(pts[bestSeg].lat, pts[bestSeg].lng, bestLat, bestLng)
        } else 0.0
        return Snapped(bestLat, bestLng, tangent, bestDist, alongRouteM)
    }

    /** Result of [snapToRoute]: on-route position + the route tangent bearing there. */
    data class Snapped(
        val lat: Double,
        val lng: Double,
        /** Route tangent bearing at the snap point, 0..360°. */
        val bearingDeg: Double,
        /** Lateral distance (m) from the input point to the route. */
        val offsetM: Double,
        /** Along-route arc distance (m) from the route START to this snap point —
         *  measured on the POLYLINE (cumArcM + on-segment partial), so progress =
         *  alongRouteM / [routeArcLengthMeters] matches MapLibre line-progress for the
         *  traveled-route trim. 0.0 when no route / no metrics. */
        val alongRouteM: Double = 0.0
    )

    /**
     * The next maneuver to announce: the first SIGNIFICANT maneuver strictly ahead.
     * A maneuver at vertex `k` is "ahead" when `k` is beyond the current segment
     * start, or equal to it+1. Trivial maneuvers (continue / new-name / a bare
     * notification) are SKIPPED so a "continue onto Main St" doesn't own the banner
     * + voice slot the way a real turn does — the driver only cares about the next
     * action. The terminal destination (arrive) is always significant.
     */
    private fun nextManeuver(r: NavRoute, segStart: Int, t: Double): RouteManeuver? {
        for (m in r.maneuvers) {
            val k = m.beginShapeIndex
            if ((k > segStart || k == segStart + 1) && maneuverImportance(m.type) >= 1) return m
        }
        return null
    }

    /**
     * The maneuver AFTER [after] (same significance filter) — the "then …" preview,
     * so the banner can show "Turn left, then turn right". Null if none remains.
     */
    private fun maneuverAfter(r: NavRoute, after: RouteManeuver): RouteManeuver? {
        var seen = false
        for (m in r.maneuvers) {
            if (seen && maneuverImportance(m.type) >= 1) return m
            if (m === after) seen = true
        }
        return null
    }

    /**
     * Significance of a Valhalla maneuver type. 0 = trivial (continue, becomes/new
     * road name, bare notification — filtered out); 1 = a real turn/keep/end-of-road
     * /u-turn; 2 = high-attention (roundabout, ramp/merge/fork, ferry, and ARRIVE so
     * the destination is never filtered). See Valhalla TripDirections::Maneuver::Type.
     */
    private fun maneuverImportance(type: Int): Int = when (type) {
        0, 1, 8 -> 0                          // none, start, continue
        // 4=destination, 5/6=dest left/right → ARRIVE (always significant)
        4, 5, 6 -> 2
        7 -> 0                                // becomes (road-name change)
        26, 27, 28, 29, 30, 31, 32, 33 -> 2   // roundabout enter/exit + ramps/merge/fork
        else -> 1                             // 9-25: turns, keeps, end-of-road, u-turns
    }

    /**
     * Distance (meters) along the route from the snapped position to the route
     * vertex at [targetIndex]: the remainder of the current segment up to its
     * end vertex, plus every full segment up to [targetIndex].
     */
    private fun distanceAlongTo(
        pts: List<GeoPoint>,
        segStart: Int,
        t: Double,
        snapLat: Double,
        snapLng: Double,
        targetIndex: Int
    ): Double {
        if (targetIndex <= segStart) {
            // Target is the current segment's start (already passed) — distance 0.
            return 0.0
        }
        // Remainder of the current segment from the snap point to vertex segStart+1.
        var dist = haversineMeters(snapLat, snapLng, pts[segStart + 1].lat, pts[segStart + 1].lng)
        var i = segStart + 1
        while (i < targetIndex && i < pts.size - 1) {
            dist += haversineMeters(pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
            i++
        }
        return dist
    }

    // ---- Geometry helpers (pure, unit-testable) ----

    /** Result of snapping a point onto a segment. */
    private data class SnapResult(
        val lat: Double,
        val lng: Double,
        /** Parametric position along the segment in [0,1] (0=A, 1=B). */
        val t: Double,
        /** Perpendicular/clamped distance from the input point, in meters. */
        val distMeters: Double
    )

    /**
     * Great-circle distance between two WGS-84 points, in meters (haversine).
     *
     * @return distance in meters
     */
    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** Initial bearing (forward azimuth) from (lat1,lng1) to (lat2,lng2), 0..360°. */
    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Shortest signed angular delta from→to, in -180..180. */
    private fun shortestArcDelta(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

    /**
     * Perpendicular distance (meters) from point P to segment A-B, clamped to
     * the segment endpoints. Uses an equirectangular projection (cos-lat
     * scaled local meters) which is accurate for the short segments of a route
     * polyline.
     *
     * @return distance in meters from P to the nearest point on segment A-B
     */
    fun pointToSegmentMeters(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Double = pointToSegment(pLat, pLng, aLat, aLng, bLat, bLng).distMeters

    /**
     * Full snap: nearest point on segment A-B to P, the parametric `t` in
     * [0,1], and the clamped distance in meters. Equirectangular local-meter
     * projection about A's latitude.
     */
    private fun pointToSegment(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): SnapResult {
        // Project to local meters: x = east, y = north, scaled by cos(lat).
        val latRad = Math.toRadians(aLat)
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(latRad)

        val ax = 0.0
        val ay = 0.0
        val bx = (bLng - aLng) * mPerDegLng
        val by = (bLat - aLat) * mPerDegLat
        val px = (pLng - aLng) * mPerDegLng
        val py = (pLat - aLat) * mPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy

        val t = if (segLenSq <= 1e-9) {
            0.0
        } else {
            (((px - ax) * dx + (py - ay) * dy) / segLenSq).coerceIn(0.0, 1.0)
        }

        val projX = ax + t * dx
        val projY = ay + t * dy
        val distMeters = sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))

        // Convert the projected local-meter point back to lat/lng.
        val snapLat = aLat + (projY / mPerDegLat)
        val snapLng = if (abs(mPerDegLng) < 1e-9) aLng else aLng + (projX / mPerDegLng)

        return SnapResult(
            lat = snapLat,
            lng = snapLng,
            t = t,
            distMeters = distMeters
        )
    }

    /**
     * Immutable per-fix guidance snapshot returned by [update].
     *
     * @property snappedLat snapped-to-route latitude in decimal degrees
     * @property snappedLng snapped-to-route longitude in decimal degrees
     * @property offRoute true once the fix has been off-route for
     *   [OFF_ROUTE_CONSECUTIVE] consecutive updates (the Activity reroutes)
     * @property arrived true when within [ARRIVAL_RADIUS_M] of the destination
     * @property currentManeuver the next maneuver to announce, or null if none
     *   remain / no route
     * @property distanceToManeuverM along-route distance to [currentManeuver],
     *   in meters
     * @property remainingDistanceM remaining along-route distance to the
     *   destination, in meters
     * @property etaSeconds estimated time remaining to the destination, in
     *   seconds
     */
    data class GuidanceState(
        val snappedLat: Double,
        val snappedLng: Double,
        val offRoute: Boolean,
        val arrived: Boolean,
        val currentManeuver: RouteManeuver?,
        val distanceToManeuverM: Double,
        val remainingDistanceM: Double,
        val etaSeconds: Double,
        /** The maneuver AFTER [currentManeuver] (the "then …" preview), or null. */
        val nextManeuver: RouteManeuver? = null
    )
}
