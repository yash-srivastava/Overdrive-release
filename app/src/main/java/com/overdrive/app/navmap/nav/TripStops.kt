package com.overdrive.app.navmap.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/**
 * When a multi-stop trip's intermediate stops are done with, so no reroute, reopened map or restore sends the car back
 * to one: driven through or left behind along the route, stood at, parked or switched off at, or dropped off at down the road (a
 * stand on the road short of it, then a U-turn). A missed entrance — driven past, even slowly or with a wait at a light before or
 * after it — is none of these: the reroute goes back to it. Nor is parking far from a big site's pin: where the car
 * stands can't tell that from a stop on the way, so the map offers to skip it instead. Pure logic over the guidance
 * engine's route; the map drops the stops it answers. Main thread only.
 */
class TripStops(private val guidance: NavGuidanceEngine) {

    /** Passed on the route: the route ahead still fits. Visited: stopped there, so plan the rest from here. */
    enum class Reason { REACHED, VISITED }

    /** The leading [count] stops are done with; [stood] at it. */
    class Done(val count: Int, val reason: Reason, val stood: Boolean = false)

    // The intermediate stops the active route goes through, in order, each with its first approach on it (null: the
    // route doesn't pass near), and how near the car has come to that point while passing it.
    private val routed = ArrayList<SearchResult>()
    private val vias = ArrayList<NavGuidanceEngine.RoutePoint?>()
    private val nearestM = ArrayList<Double>()

    // Distance actually driven (moving fixes only: a parked car's GPS wander isn't driving).
    private var drivenM = 0.0
    private var droppedAtM = 0.0
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    // recent moving positions, for the way the car came to a stand
    private val trailLat = DoubleArray(TRAIL)
    private val trailLng = DoubleArray(TRAIL)
    private var trailN = 0

    // The next stop's evidence: the current stand, and a stand on the road short of it being watched (the way the car
    // came, unit east/north; where along that the stop lies; the distance driven when it ended).
    private var evidenceFor: SearchResult? = null
    private var nextNearestM = Double.MAX_VALUE
    private var standSinceMs = 0L
    private var standLat = Double.NaN
    private var standLng = Double.NaN
    private var standOnRoute = false
    private var nearStoodMs = 0L
    private var nearbyStoodMs = 0L
    private var nearAwayMs = 0L
    private var nearbyAwayMs = 0L
    private var parkedAtMs = 0L
    private var offNearMs = 0L
    private var lastTickMs = 0L
    private var watchLat = Double.NaN
    private var watchLng = Double.NaN
    private var watchUx = 0.0
    private var watchUy = 0.0
    private var watchStopAlongM = 0.0
    private var watchDrivenM = 0.0
    private var watchProgressM = 0.0

    /** A route was just started on the engine with the car at (lat, lng), through these intermediate [stops] in order. */
    fun routeInstalled(stops: List<SearchResult>, carLat: Double, carLng: Double) {
        // (how near the car came to a stop's point carries over if the new route has it in about the same place)
        val before = routed.indices.associate { routed[it] to Pair(vias[it], nearestM[it]) }
        routed.clear(); vias.clear(); nearestM.clear()
        // (from where the car is on it: a route computed a while ago may start behind it)
        var from = if (carLat.isNaN()) 0.0
        else ((guidance.approachAfter(carLat, carLng, 0.0)?.alongM ?: 0.0) - START_BACK_M).coerceAtLeast(0.0)
        for (s in stops) {
            val a = guidance.approachAfter(s.lat, s.lng, from)
            val via = a?.takeIf { guidance.haversineMeters(it.lat, it.lng, s.lat, s.lng) <= VIA_MAX_M }
            routed.add(s)
            vias.add(via)
            val old = before.entries.firstOrNull { it.key === s }?.value
            val keep = via != null && old?.first != null &&
                guidance.haversineMeters(via.lat, via.lng, old.first!!.lat, old.first!!.lng) <= SAME_POINT_M
            nearestM.add(if (keep) old!!.second else Double.MAX_VALUE)
            // the next stop's point comes after this one's, even one the route only gets near (a park, an airport)
            if (a != null) from = a.alongM
        }
        // (a watched stand is judged against the new route from where the car is on it)
        val carAlongM = if (carLat.isNaN()) 0.0 else guidance.approachAfter(carLat, carLng, 0.0)?.alongM ?: 0.0
        if (!watchLat.isNaN()) watchProgressM = carAlongM
        if (!destWatchLat.isNaN()) destWatchProgressM = carAlongM
        lastProgressM = -1.0
    }

    /** The car has driven on since the last stop left (so standing by the next one can be a visit to it). */
    val armedToReach: Boolean get() = drivenM - droppedAtM >= REACHED_AFTER_M

    /**
     * No stop the car was at has left the trip, or the car has driven on along the route since, or is nearer the
     * destination at (destLat, destLng) than that stop (standing by the destination counts; standing at that stop doesn't).
     */
    fun clearOfLastStop(destLat: Double, destLng: Double): Boolean {
        if (leftLat.isNaN() || onRouteSinceLeftM >= CLEAR_ON_ROUTE_M) return true
        if (lastLat.isNaN() || destLat.isNaN()) return false
        val fromStopM = guidance.haversineMeters(lastLat, lastLng, leftLat, leftLng)
        // (stood at it, then moved on and away: a stand now is for the destination)
        if (leftStood && armedToReach && fromStopM > REACHED_M) return true
        return guidance.haversineMeters(lastLat, lastLng, destLat, destLng) < fromStopM
    }
    // the last stop the car was at when it left the trip (NaN: none), and how far it has driven on along the route since
    private var leftLat = Double.NaN
    private var leftLng = Double.NaN
    private var onRouteSinceLeftM = 0.0
    private var leftStood = false
    private var lastProgressM = -1.0

    /** A restored trip whose last stop, at (lat, lng), the car was at. */
    fun stopLeftAt(lat: Double, lng: Double) {
        leftLat = lat
        leftLng = lng
        onRouteSinceLeftM = 0.0
        leftStood = false
        droppedAtM = drivenM
    }

    /** The trip's destination changed: its stops behind are no one's. */
    fun forgetLeftStop() {
        leftLat = Double.NaN
        leftLng = Double.NaN
        leftStood = false
    }

    /** The car switched to another route through the same stops (an alternative, a faster one). */
    fun routeSwitched(carLat: Double, carLng: Double) = routeInstalled(ArrayList(routed), carLat, carLng)

    /** Guidance to a (new) destination began. */
    fun begin() {
        routed.clear(); vias.clear(); nearestM.clear()
        drivenM = 0.0
        droppedAtM = 0.0
        lastLat = Double.NaN
        lastLng = Double.NaN
        lastTickMs = 0L
        trailN = 0
        evidenceFor = null
        leftLat = Double.NaN
        leftLng = Double.NaN
        leftStood = false
        lastProgressM = -1.0
        destStandSinceMs = 0L
        destWatchLat = Double.NaN
    }

    /**
     * With no stop left, a guidance tick for the destination at (destLat, destLng): true once the car, having stood on
     * the road short of it a while (a drop-off, a pick-up at the school gate), went back the way it came (against the
     * route). Driving on to it is its own arrival; turning off after a stand isn't told from a wrong lane at a light.
     */
    fun destinationTick(destLat: Double, destLng: Double, lat: Double, lng: Double, speedMps: Double, nowMs: Long): Boolean {
        val standing = speedMps <= STANDING_MPS
        if (standing && destStandSinceMs == 0L) {
            destStandSinceMs = nowMs
            destStandLat = lat
            destStandLng = lng
            destStandOnRoute = guidance.onRoute
        } else if (!standing && destStandSinceMs != 0L) {
            if (destStandOnRoute && nowMs - destStandSinceMs >= DEST_DROP_OFF_STAND_MS &&
                guidance.haversineMeters(destStandLat, destStandLng, destLat, destLng) <= DROP_OFF_NEAR_M
            ) {
                approachFrom(destStandLat, destStandLng)?.let { (ux, uy) ->
                    destWatchLat = destStandLat
                    destWatchLng = destStandLng
                    destUx = ux
                    destUy = uy
                    destAlongM = alongFrom(destWatchLat, destWatchLng, ux, uy, destLat, destLng)
                    destWatchDrivenM = drivenM
                    destWatchProgressM = guidance.progressM
                    destWatchForLat = destLat
                    destWatchForLng = destLng
                    // (stood past it: its own arrival's, or a wait after missing it)
                    if (destAlongM <= 0.0) destWatchLat = Double.NaN
                }
            }
            destStandSinceMs = 0L
        }
        if (destWatchLat.isNaN()) return false
        // (gone well away since, or another destination: done watching)
        if (guidance.haversineMeters(lat, lng, destLat, destLng) > DEST_WATCH_MAX_M ||
            guidance.haversineMeters(destLat, destLng, destWatchForLat, destWatchForLng) > SAME_POINT_M
        ) {
            destWatchLat = Double.NaN
            return false
        }
        val along = alongFrom(destWatchLat, destWatchLng, destUx, destUy, lat, lng)
        if (along > destAlongM) {
            destWatchLat = Double.NaN
            return false
        }
        // (against the route, not along it: a route that U-turns to reach it goes back past the stand too)
        return drivenM - destWatchDrivenM >= BACK_M && guidance.progressM <= destWatchProgressM + BACK_M && along < -BACK_M
    }
    private var destStandSinceMs = 0L
    private var destStandLat = Double.NaN
    private var destStandLng = Double.NaN
    private var destStandOnRoute = false
    private var destWatchLat = Double.NaN
    private var destWatchLng = Double.NaN
    private var destUx = 0.0
    private var destUy = 0.0
    private var destAlongM = 0.0
    private var destWatchDrivenM = 0.0
    private var destWatchProgressM = 0.0
    private var destWatchForLat = Double.NaN
    private var destWatchForLng = Double.NaN

    /** These stops left the trip; [atStop]: done with there (not skipped, nor passed by), [stood] at the last. */
    fun dropped(stops: List<SearchResult>, atStop: Boolean, stood: Boolean = false) {
        for (s in stops) {
            val i = routed.indexOfFirst { it === s }
            if (i >= 0) { routed.removeAt(i); vias.removeAt(i); nearestM.removeAt(i) }
        }
        droppedAtM = drivenM
        destStandSinceMs = 0L
        destWatchLat = Double.NaN
        if (atStop && stops.isNotEmpty()) {
            leftLat = stops.last().lat
            leftLng = stops.last().lng
            onRouteSinceLeftM = 0.0
            leftStood = stood
        }
        evidenceFor = null
    }

    /** The route the car is on goes through exactly these stops, in this order (not one edited since). */
    fun inStep(stops: List<SearchResult>): Boolean =
        routed.size == stops.size && routed.indices.all { routed[it] === stops[it] }

    /** The car has been within [m] of the next stop since it became next. */
    fun nearNext(m: Double): Boolean = nextNearestM <= m

    /**
     * Guidance tick with the car at (lat, lng), [parked] in P, [switchedOff]: how many leading [stops] (the trip's
     * intermediate ones, in order) are done with now, or null.
     */
    fun tick(
        stops: List<SearchResult>, lat: Double, lng: Double, speedMps: Double, nowMs: Long, parked: Boolean = false,
        switchedOff: Boolean = false
    ): Done? {
        val dtMs = if (lastTickMs == 0L) 0L else (nowMs - lastTickMs).coerceIn(0L, MAX_TICK_MS)
        lastTickMs = nowMs
        val moved = if (!lastLat.isNaN() && speedMps >= MOVING_MPS) guidance.haversineMeters(lastLat, lastLng, lat, lng) else 0.0
        drivenM += moved
        // (on along the route: progress made on it)
        val progress = guidance.progressM
        if (moved > 0.0 && guidance.onRoute && lastProgressM >= 0.0 && progress > lastProgressM) {
            onRouteSinceLeftM += min(moved, progress - lastProgressM)
        }
        lastProgressM = progress
        lastLat = lat
        lastLng = lng
        if (speedMps >= TRAIL_MPS) {
            trailLat[trailN % TRAIL] = lat; trailLng[trailN % TRAIL] = lng; trailN++
        }
        if (stops.isEmpty()) return null
        throughCount(stops, lat, lng, THROUGH_ON_M)?.let {
            return Done(it, Reason.REACHED, stood = stops[0] === evidenceFor && nearStoodMs >= PASS_STOOD_MS)
        }

        val s = stops[0]
        if (s !== evidenceFor) {
            evidenceFor = s
            nextNearestM = Double.MAX_VALUE
            standSinceMs = 0L
            nearStoodMs = 0L
            nearbyStoodMs = 0L
            nearAwayMs = 0L
            nearbyAwayMs = 0L
            parkedAtMs = 0L
            offNearMs = 0L
            watchLat = Double.NaN
        }
        val d = guidance.haversineMeters(lat, lng, s.lat, s.lng)
        nextNearestM = min(nextNearestM, d)
        val standing = speedMps <= STANDING_MPS
        // time stood near it, adding up over stop-and-go (a queue to its window): at it off the road, or right by it
        // on the road (a forecourt by it; a queue at a junction near it, across a divided road or down a side street
        // from it, isn't); kept through a brief drift out, gone once the car has really left
        val counts = drivenM - droppedAtM >= REACHED_AFTER_M && standing
        if (d > REACHED_M) nearAwayMs += dtMs else nearAwayMs = 0L
        if (d > VISIT_NEAR_M) nearbyAwayMs += dtMs else nearbyAwayMs = 0L
        nearStoodMs = when {
            d > REACHED_M + LEFT_SLACK_M || nearAwayMs >= LEFT_AFTER_MS -> 0L
            counts && (d <= AT_STOP_ON_ROAD_M || (d <= REACHED_M && !guidance.onRoute)) -> nearStoodMs + dtMs
            else -> nearStoodMs
        }
        nearbyStoodMs = when {
            d > VISIT_NEAR_M + LEFT_SLACK_M || nearbyAwayMs >= LEFT_AFTER_MS -> 0L
            counts && d <= VISIT_NEAR_M -> nearbyStoodMs + dtMs
            else -> nearbyStoodMs
        }
        // switched off near it, or in P off the road by it (its car park, a forecourt; not a light on the road)
        val armed = drivenM - droppedAtM >= REACHED_AFTER_M
        if (armed && switchedOff && speedMps < MOVING_MPS && d <= REACHED_M) return Done(1, Reason.VISITED, stood = true)
        // (switched off a while further off: parked for it, as when the head unit sleeps there)
        offNearMs = if (armed && switchedOff && d <= VISIT_NEAR_M) offNearMs + dtMs else 0L
        if (offNearMs >= SWITCHED_OFF_VISIT_MS) return Done(1, Reason.VISITED, stood = true)
        parkedAtMs = when {
            !armed || !parked || d > REACHED_M -> 0L
            // (kept through a fix drifting onto the road)
            standing && guidance.offRouteM >= OFF_ROAD_M -> parkedAtMs + dtMs
            else -> parkedAtMs
        }
        if (standing && standSinceMs == 0L) {
            standSinceMs = nowMs
            standLat = lat
            standLng = lng
            standOnRoute = guidance.onRoute
        } else if (!standing && standSinceMs != 0L) {
            // a stand on the road just ended: one long enough for a drop-off, short of the stop, is watched
            if (drivenM - droppedAtM >= DROP_OFF_AFTER_M && standOnRoute && nowMs - standSinceMs >= DROP_OFF_STAND_MS) {
                watchStand(s)
            }
            standSinceMs = 0L
        }
        // stood at it a good while (a forecourt, a kerb, a drive-through queue), or near it a long while (a big site's car
        // park): longer than a light or a queue by it. A short stand near a stop says nothing (a light, the far side of
        // a divided road, another street); driving on through it, a drop-off U-turn or a skip says the rest.
        if (nearStoodMs >= LONG_STAND_MS || nearbyStoodMs >= VISIT_STAND_MS || parkedAtMs >= PARKED_AT_MS) {
            return Done(1, Reason.VISITED, stood = true)
        }
        // after a drop-off short of it or just past it, the way the car goes: on past it (a missed entrance, or on to it),
        // nothing; driven back the way it came (a U-turn, even from right by it), back past it if it was behind: done
        // with it
        if (!watchLat.isNaN()) {
            val along = alongWatch(lat, lng)
            val behind = watchStopAlongM <= 0.0
            when {
                along > (if (behind) THROUGH_ON_M else watchStopAlongM) -> watchLat = Double.NaN
                // (back against the route, not along it: a route round the block passes behind the stand too)
                along < (if (behind) watchStopAlongM else 0.0) - BACK_M && drivenM - watchDrivenM >= BACK_M &&
                    guidance.progressM <= watchProgressM + BACK_M -> {
                    watchLat = Double.NaN
                    return Done(1, Reason.VISITED, stood = true)
                }
            }
        }
        return null
    }

    /**
     * Before a reroute with the car at (lat, lng): leading stops it has just driven through (at a stop's point on the
     * route and turned off right after), so the reroute doesn't send it back; null when none.
     */
    fun beforeReroute(stops: List<SearchResult>, lat: Double, lng: Double): Done? {
        throughCount(stops, lat, lng, -THROUGH_AT_REROUTE_M)?.let {
            return Done(it, Reason.REACHED, stood = stops[0] === evidenceFor && nearStoodMs >= PASS_STOOD_MS)
        }
        // (stood a minute right by the next stop, now leaving it another way: a quick fill-up, a pick-up)
        if (stops.isNotEmpty() && stops[0] === evidenceFor && nearStoodMs >= AT_STOP_BEFORE_REROUTE_MS) {
            return Done(1, Reason.VISITED, stood = true)
        }
        return null
    }

    // How many leading stops the car has driven through (near a stop's point, then [onM] on) or left well behind.
    private fun throughCount(stops: List<SearchResult>, lat: Double, lng: Double, onM: Double): Int? {
        val progress = guidance.progressM
        // (not on a route planned for other stops, or another order: its points aren't these stops')
        if (progress < 0.0 || !inStep(stops)) return null
        var through = -1
        for (k in stops.indices) {
            val i = routed.indexOfFirst { it === stops[k] }
            // (not past a stop the route doesn't know yet: it was added ahead of the later ones)
            if (i < 0) break
            val v = vias[i] ?: continue
            if (abs(progress - v.alongM) <= PASSING_ALONG_M) {
                nearestM[i] = min(nearestM[i], guidance.haversineMeters(lat, lng, v.lat, v.lng))
            }
            if ((nearestM[i] <= THROUGH_NEAR_M && progress >= v.alongM + onM) || progress >= v.alongM + LEFT_BEHIND_M) {
                through = k
            }
        }
        return if (through >= 0) through + 1 else null
    }

    // The way the car came to (lat, lng), as a unit vector east/north: from the latest trail point this far back (or
    // the oldest kept, if near enough); null when the trail is too short.
    private fun approachFrom(lat: Double, lng: Double): Pair<Double, Double>? {
        var pick = -1
        for (j in 1..min(trailN, TRAIL)) {
            val idx = (trailN - j) % TRAIL
            val back = guidance.haversineMeters(trailLat[idx], trailLng[idx], lat, lng)
            if (back >= APPROACH_M) { pick = idx; break }
            if (back >= APPROACH_MIN_M) pick = idx
        }
        if (pick < 0) return null
        val back = guidance.haversineMeters(trailLat[pick], trailLng[pick], lat, lng)
        val (ex, ny) = local(lat, lng, trailLat[pick], trailLng[pick])
        return Pair(-ex / back, -ny / back)
    }

    // Watches the stand that just ended if the stop lay ahead of the way the car came to it, within reach.
    private fun watchStand(s: SearchResult) {
        if (guidance.haversineMeters(standLat, standLng, s.lat, s.lng) > DROP_OFF_NEAR_M) return
        val (ux, uy) = approachFrom(standLat, standLng) ?: return
        watchUx = ux
        watchUy = uy
        watchLat = standLat
        watchLng = standLng
        watchStopAlongM = alongWatch(s.lat, s.lng)
        watchDrivenM = drivenM
        watchProgressM = guidance.progressM
        // the stop behind the car: a drop-off at the kerb just past it, or (further, or off the road) a wait after missing it
        if (watchStopAlongM <= 0.0 &&
            (watchStopAlongM <= -REACHED_M || guidance.haversineMeters(standLat, standLng, s.lat, s.lng) > AT_KERB_M)
        ) watchLat = Double.NaN
    }

    // How far along the watched stand's approach direction (lat, lng) lies from the stand.
    private fun alongWatch(lat: Double, lng: Double): Double = alongFrom(watchLat, watchLng, watchUx, watchUy, lat, lng)

    // How far along (ux, uy) from (oLat, oLng) the point (lat, lng) lies.
    private fun alongFrom(oLat: Double, oLng: Double, ux: Double, uy: Double, lat: Double, lng: Double): Double {
        val (ex, ny) = local(oLat, oLng, lat, lng)
        return ex * ux + ny * uy
    }

    // (lat2, lng2) in metres east and north of (lat1, lng1).
    private fun local(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Pair<Double, Double> {
        val m = 6_371_000.0 * Math.PI / 180.0
        return Pair((lng2 - lng1) * m * cos(Math.toRadians(lat1)), (lat2 - lat1) * m)
    }

    companion object {
        /** A stop the route passes further off than this has no point on it (its approach still orders the next). */
        const val VIA_MAX_M = 300.0
        /** A new route's point for a stop this near the old one's is the same place. */
        const val SAME_POINT_M = 30.0
        /** Stop points are searched from this far behind where the car is on a newly started route. */
        const val START_BACK_M = 50.0
        /** The car's nearest pass to a stop's point counts within this much along the route of it. */
        const val PASSING_ALONG_M = 200.0
        /** Within this of a stop's point on the route, then this far on along it: driven through. */
        const val THROUGH_NEAR_M = 25.0
        const val THROUGH_ON_M = 50.0
        /** At a reroute, reaching the point (bar this) is enough: the car turned off right after it. */
        const val THROUGH_AT_REROUTE_M = 10.0
        /** This far along the route beyond a stop's point without leaving the route: left behind, not routed back. */
        const val LEFT_BEHIND_M = 300.0
        /** Stood near a stop, having driven this far since the last, this long in all within this of it: visited. */
        const val REACHED_M = 60.0
        const val REACHED_AFTER_M = 30.0
        /** Stood this long in all within [REACHED_M] of it: visited (longer than a light or a queue). */
        const val LONG_STAND_MS = 210_000L
        /** Stood this long in all within this of it: visited (a big site). */
        const val VISIT_NEAR_M = 150.0
        const val VISIT_STAND_MS = 10 * 60_000L
        /** A stand this near a stop's pin is at its kerb (a forecourt or car park further in is missed from the road). */
        const val AT_KERB_M = 40.0
        /** At the destination a quicker drop-off counts (going back or off the route after it is what tells). */
        const val DEST_DROP_OFF_STAND_MS = 8_000L
        /** The destination's drop-off watch ends this far from it. */
        const val DEST_WATCH_MAX_M = 300.0
        /** Stood this long by a stop it then drove through: at it, not passing it by. */
        const val PASS_STOOD_MS = 10_000L
        /** Switched off this long within [VISIT_NEAR_M] of it: visited (as when the head unit sleeps there). */
        const val SWITCHED_OFF_VISIT_MS = 120_000L
        /** Driven on this far along the route since the last stop the car was at left the trip: clear of it. */
        const val CLEAR_ON_ROUTE_M = 100.0
        /** In P off the road this long within [REACHED_M] of it, having driven [REACHED_AFTER_M] since the last: visited. */
        const val PARKED_AT_MS = 3_000L
        /** This far from the route, a car in P is off the road (a light, a queue on it are nearer). */
        const val OFF_ROAD_M = 30.0
        /** Standing is at most this fast; driving (for the distance driven) at least this. */
        const val STANDING_MPS = 1.5
        const val MOVING_MPS = 2.0
        private const val TRAIL_MPS = 1.0
        private const val TRAIL = 60
        /** The way the car came to a stand: from where it was this far before (at least the shorter). */
        private const val APPROACH_M = 150.0
        private const val APPROACH_MIN_M = 30.0
        /**
         * A drop-off: stood on the road this long, within this short of the stop, having driven this far since the last
         * one left; then drove this far back the way it came.
         */
        const val DROP_OFF_STAND_MS = 15_000L
        const val DROP_OFF_NEAR_M = 200.0
        const val DROP_OFF_AFTER_M = 150.0
        const val BACK_M = 30.0
        /** On the road, standing counts this near a stop only (a forecourt by it, not a junction near it). */
        const val AT_STOP_ON_ROAD_M = 30.0
        /** At a reroute, this much stand time right by the next stop: visited. */
        const val AT_STOP_BEFORE_REROUTE_MS = 60_000L
        /** Stand time near a stop survives a drift out this far or this long. */
        const val LEFT_SLACK_M = 60.0
        const val LEFT_AFTER_MS = 20_000L
        private const val MAX_TICK_MS = 2_000L
    }
}
