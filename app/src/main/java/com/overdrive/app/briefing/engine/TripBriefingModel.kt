package com.overdrive.app.briefing.engine

import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.exp
import kotlin.math.max

/** Learned routines over a trip history. Immutable; rebuild when trips, names or overrides change. */
class BriefingModel internal constructor(
    val places: List<BriefPlace>,
    val routines: List<Routine>,
    private val config: BriefingConfig,
    internal val table: TripTable = TripTable.EMPTY,
    private val overrides: BriefingOverrides = BriefingOverrides(),
    zone: ZoneId = ZoneOffset.UTC,
) {
    private val clock = LocalClock(zone, config.weekendDays)
    private val match = OverrideMatcher(overrides, config.radiusM)
    private val selfTestResult: SelfTest by lazy { replay(null) }
    private val unnamedPlaces: List<BriefPlace> by lazy {
        val inRoutine = HashSet<BriefPlace>()
        for (r in routines) {
            inRoutine.add(r.from)
            inRoutine.add(r.to)
        }
        places.filter { it.label == null && it.role == null && it in inRoutine }.sortedByDescending { it.visits }
    }

    /** The destination to offer at [nowMs] from the origin, or null when nothing is confident enough. */
    fun predict(nowMs: Long, originLat: Double, originLng: Double): Offer? {
        if (!usablePoint(originLat, originLng) || table.departures.size != places.size * 2) return null
        val origin = nearestPlace(originLat, originLng)
        if (origin < 0) return null
        val dt = clock.dayTypeIndex(nowMs)
        val nowMin = clock.minuteOfDay(nowMs)
        val list = table.departures[origin * 2 + dt]
        val count = table.startedBefore(list, nowMs)
        val scorer = Scorer(places.size, config)
        val best = scorer.score(table, list, count, nowMs, nowMin)
        if (best < 0) return null
        val from = places[origin]
        val dest = places[best]
        val dayType = dayTypeOf(dt)
        if (match.hidden(from, dest, dayType) ||
            match.declined(from, dest, dayType, nowMs, config.declineLimit, config.declineWindowDays)
        ) return null

        val durS = DoubleArray(scorer.candidates)
        val distKm = DoubleArray(scorer.candidates)
        var k = 0
        var lastId = -1L
        for (s in 0 until count) {
            val j = list[s]
            if (table.endPlace[j] != best || minuteGap(table.startMin[j], nowMin) > config.windowMin) continue
            durS[k] = (table.endMs[j] - table.startMs[j]) / 1000.0
            distKm[k] = table.distanceKm[j]
            k++
            lastId = table.id[j]
        }
        return Offer(
            routineId = routineId(from, dest, dayType),
            dest = dest,
            dayType = dayType,
            share = scorer.share,
            matches = scorer.candidates,
            medianDurationS = Math.round(medianOf(durS, k)).toInt(),
            medianDistanceKm = medianOf(distKm, k),
            app = match.app(dest),
            lastTripId = lastId,
            from = from,
        )
    }

    /** Unnamed places that are an end of some routine, most visited first. */
    fun unnamed(): List<BriefPlace> = unnamedPlaces

    /** Replays the history in time order, predicting each trip from strictly earlier ones. */
    fun selfTest(): SelfTest = selfTestResult

    /**
     * Self-test core. Each trip's origin is the place its start point belongs to; hidden routines
     * and declines are not applied. [trace], when given, gets the offered place per trip or -1.
     */
    internal fun replay(trace: IntArray?): SelfTest {
        val t = table
        val n = t.size
        if (n == 0 || t.departures.size != places.size * 2) return SelfTest(n, 0, 0, emptyList(), null)
        val scorer = Scorer(places.size, config)
        val seen = IntArray(t.departures.size)
        val perRoutine = HashMap<Long, IntArray>()
        var offers = 0
        var correct = 0
        var firstOfferMs: Long? = null
        for (i in 0 until n) {
            val g = t.startPlace[i] * 2 + t.dayType[i]
            val list = t.departures[g]
            var c = seen[g]
            while (c < list.size && t.startMs[list[c]] < t.startMs[i]) c++
            seen[g] = c
            val best = scorer.score(t, list, c, t.startMs[i], t.startMin[i])
            if (trace != null) trace[i] = best
            if (best < 0) continue
            offers++
            if (firstOfferMs == null) firstOfferMs = t.startMs[i]
            val ok = best == t.endPlace[i]
            if (ok) correct++
            val key = (t.startPlace[i].toLong() * places.size + best) * 2 + t.dayType[i]
            val s = perRoutine.getOrPut(key) { IntArray(2) }
            s[0]++
            if (ok) s[1]++
        }
        val pc = places.size.toLong()
        val scores = perRoutine.map { (key, s) ->
            val od = key / 2
            RoutineScore(
                routineId(places[(od / pc).toInt()], places[(od % pc).toInt()], dayTypeOf((key % 2).toInt())),
                s[0],
                s[1],
            )
        }.sortedWith(compareByDescending<RoutineScore> { it.offers }.thenBy { it.routineId })
        val firstDay = firstOfferMs?.let { ((it - t.startMs[0]) / DAY_MS).toInt() }
        return SelfTest(n, offers, correct, scores, firstDay)
    }

    private fun nearestPlace(lat: Double, lng: Double): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (p in places.indices) {
            val d = haversineM(lat, lng, places[p].lat, places[p].lng)
            if (d <= config.radiusM && d < bestD) {
                best = p
                bestD = d
            }
        }
        return best
    }
}

object TripBriefingModel {
    private const val MIN_ROLE_EVIDENCE = 5
    private const val WORK_MIN_DWELL_MS = 4 * 3_600_000L

    @JvmStatic
    @JvmOverloads
    fun build(
        trips: List<BriefTrip>,
        named: List<NamedPlace>,
        overrides: BriefingOverrides,
        @Suppress("UNUSED_PARAMETER") nowMs: Long,
        zone: ZoneId,
        config: BriefingConfig = BriefingConfig(),
    ): BriefingModel {
        val clock = LocalClock(zone, config.weekendDays)
        val usable = trips.filter {
            usablePoint(it.startLat, it.startLng) && usablePoint(it.endLat, it.endLng) && it.endMs >= it.startMs
        }.sortedWith(TRIP_ORDER)
        val n = usable.size
        val lat = DoubleArray(2 * n)
        val lng = DoubleArray(2 * n)
        for ((i, t) in usable.withIndex()) {
            lat[i] = t.startLat
            lng[i] = t.startLng
            lat[n + i] = t.endLat
            lng[n + i] = t.endLng
        }
        val clusters = PlaceClusterer.cluster(lat, lng, config.radiusM)
        val table = tripTable(usable, clusters, clock)

        val names = named.filter { usablePoint(it.lat, it.lng) }
        val labels = arrayOfNulls<String>(clusters.size)
        val roles = arrayOfNulls<PlaceRole>(clusters.size)
        val userNamed = BooleanArray(clusters.size)
        nameFromSources(clusters, names, config.radiusM, labels, roles, userNamed)
        // the driver's own name wins over a saved Home or Work
        for (p in roles.indices) if (userNamed[p]) roles[p] = null
        val sleepsAt = overnightPlace(table, clock, roles)
        // A place the driver named keeps that name: no inferred Home or Work on it.
        if (sleepsAt >= 0 && names.none { it.source == PlaceSource.SAVED_HOME } && !userNamed[sleepsAt]) {
            roles[sleepsAt] = PlaceRole.HOME
        }
        if (names.none { it.source == PlaceSource.SAVED_WORK }) {
            // A saved Home that matched no place must not leave the real one eligible as Work.
            inferWork(table, clock, roles, exclude = if (PlaceRole.HOME in roles) -1 else sleepsAt, userNamed)
        }
        val places = List(clusters.size) {
            BriefPlace(clusters.lat[it], clusters.lng[it], labels[it], roles[it], clusters.visits[it])
        }
        val routines = routinesOf(table, places, config, OverrideMatcher(overrides, config.radiusM))
        return BriefingModel(places, routines, config, table, overrides, zone)
    }

    private val TRIP_ORDER = Comparator<BriefTrip> { a, b ->
        var c = a.startMs.compareTo(b.startMs)
        if (c == 0) c = a.id.compareTo(b.id)
        if (c == 0) c = a.endMs.compareTo(b.endMs)
        if (c == 0) c = a.startLat.compareTo(b.startLat)
        if (c == 0) c = a.startLng.compareTo(b.startLng)
        if (c == 0) c = a.endLat.compareTo(b.endLat)
        if (c == 0) c = a.endLng.compareTo(b.endLng)
        if (c == 0) c = a.distanceKm.compareTo(b.distanceKm)
        c
    }

    private val NO_TRIPS = IntArray(0)

    private fun tripTable(trips: List<BriefTrip>, clusters: PlaceClusters, clock: LocalClock): TripTable {
        val n = trips.size
        val startPlace = IntArray(n) { clusters.assign[it] }
        val endPlace = IntArray(n) { clusters.assign[n + it] }
        val dayType = IntArray(n) { clock.dayTypeIndex(trips[it].startMs) }
        val groupSize = IntArray(clusters.size * 2)
        for (i in 0 until n) if (startPlace[i] != endPlace[i]) groupSize[startPlace[i] * 2 + dayType[i]]++
        val departures = Array(groupSize.size) { if (groupSize[it] == 0) NO_TRIPS else IntArray(groupSize[it]) }
        val fill = IntArray(groupSize.size)
        for (i in 0 until n) {
            if (startPlace[i] == endPlace[i]) continue
            val g = startPlace[i] * 2 + dayType[i]
            departures[g][fill[g]++] = i
        }
        return TripTable(
            id = LongArray(n) { trips[it].id },
            startMs = LongArray(n) { trips[it].startMs },
            endMs = LongArray(n) { trips[it].endMs },
            distanceKm = DoubleArray(n) { trips[it].distanceKm },
            startMin = IntArray(n) { clock.minuteOfDay(trips[it].startMs) },
            day = LongArray(n) { Math.floorDiv(clock.localSec(trips[it].startMs), 86_400L) },
            dayType = dayType,
            startPlace = startPlace,
            endPlace = endPlace,
            departures = departures,
        )
    }

    private fun rank(source: PlaceSource): Int = when (source) {
        PlaceSource.USER -> 0
        PlaceSource.SAVED_HOME, PlaceSource.SAVED_WORK -> 1
        PlaceSource.SAVED -> 2
        PlaceSource.SAFE -> 3
        PlaceSource.RECENT -> 4
    }

    /** Label = best-ranked, then nearest, name within the radius; role = nearest saved Home/Work. */
    private fun nameFromSources(
        clusters: PlaceClusters,
        names: List<NamedPlace>,
        radiusM: Double,
        labels: Array<String?>,
        roles: Array<PlaceRole?>,
        userNamed: BooleanArray,
    ) {
        if (names.isEmpty()) return
        for (p in 0 until clusters.size) {
            var labelRank = Int.MAX_VALUE
            var labelD = Double.MAX_VALUE
            var roleD = Double.MAX_VALUE
            for (np in names) {
                val d = haversineM(clusters.lat[p], clusters.lng[p], np.lat, np.lng)
                if (!(d <= radiusM)) continue
                val r = rank(np.source)
                if (np.name.isNotBlank() && (r < labelRank || (r == labelRank && d < labelD))) {
                    labels[p] = np.name.trim()
                    labelRank = r
                    labelD = d
                }
                val role = when (np.source) {
                    PlaceSource.SAVED_HOME -> PlaceRole.HOME
                    PlaceSource.SAVED_WORK -> PlaceRole.WORK
                    else -> null
                }
                if (role != null && d < roleD) {
                    roles[p] = role
                    roleD = d
                }
            }
            userNamed[p] = labelRank == rank(PlaceSource.USER)
        }
    }

    /**
     * The role-less place with the most overnight parks (last arrival of each local day, the day
     * ending at 03:00), with at least [MIN_ROLE_EVIDENCE]; -1 if none.
     */
    private fun overnightPlace(t: TripTable, clock: LocalClock, roles: Array<PlaceRole?>): Int {
        val lastArrival = HashMap<Long, Int>()
        for (i in 0 until t.size) {
            val night = Math.floorDiv(clock.localSec(t.endMs[i]) - 3 * 3600L, 86_400L)
            val cur = lastArrival[night]
            if (cur == null || t.endMs[i] >= t.endMs[cur]) lastArrival[night] = i
        }
        val nights = IntArray(roles.size)
        for (i in lastArrival.values) nights[t.endPlace[i]]++
        var home = -1
        for (p in roles.indices) {
            if (roles[p] != null || nights[p] < MIN_ROLE_EVIDENCE) continue
            if (home < 0 || nights[p] > nights[home]) home = p
        }
        return home
    }

    /** Work = most weekday arrivals 07:00–13:00 with a median stay of 4 h or more, Home excluded. */
    private fun inferWork(t: TripTable, clock: LocalClock, roles: Array<PlaceRole?>, exclude: Int, userNamed: BooleanArray) {
        val arrivals = IntArray(roles.size)
        val dwell = arrayOfNulls<ArrayList<Long>>(roles.size)
        for (i in 0 until t.size) {
            val p = t.endPlace[i]
            val end = t.endMs[i]
            if (p == exclude || roles[p] != null || userNamed[p] || clock.dayTypeIndex(end) == 1) continue
            val min = clock.minuteOfDay(end)
            if (min < 7 * 60 || min >= 13 * 60) continue
            arrivals[p]++
            val next = firstStartAfter(t.startMs, end)
            if (next < t.size) (dwell[p] ?: ArrayList<Long>().also { dwell[p] = it }).add(t.startMs[next] - end)
        }
        var work = -1
        for (p in roles.indices) {
            if (arrivals[p] < MIN_ROLE_EVIDENCE) continue
            val stays = dwell[p] ?: continue
            if (medianOf(DoubleArray(stays.size) { stays[it].toDouble() }) < WORK_MIN_DWELL_MS) continue
            if (work < 0 || arrivals[p] > arrivals[work]) work = p
        }
        if (work >= 0) roles[work] = PlaceRole.WORK
    }

    private fun firstStartAfter(startMs: LongArray, ms: Long): Int {
        var lo = 0
        var hi = startMs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (startMs[mid] <= ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun routinesOf(
        t: TripTable,
        places: List<BriefPlace>,
        config: BriefingConfig,
        match: OverrideMatcher,
    ): List<Routine> {
        val pc = places.size.toLong()
        val groups = LinkedHashMap<Long, ArrayList<Int>>()
        for (i in 0 until t.size) {
            if (t.startPlace[i] == t.endPlace[i]) continue
            val key = (t.startPlace[i] * pc + t.endPlace[i]) * 2 + t.dayType[i]
            groups.getOrPut(key) { ArrayList() }.add(i)
        }
        val out = ArrayList<Routine>()
        val minTrips = max(1, config.routineMinTrips)
        for ((key, trips) in groups) {
            if (trips.size < minTrips) continue
            var days = 0
            var lastDay = Long.MIN_VALUE
            for (i in trips) if (t.day[i] != lastDay) { days++; lastDay = t.day[i] }
            if (days < config.minDays) continue
            val od = key / 2
            val from = places[(od / pc).toInt()]
            val to = places[(od % pc).toInt()]
            val dayType = dayTypeOf((key % 2).toInt())
            val last = trips[trips.size - 1]
            out.add(
                Routine(
                    id = routineId(from, to, dayType),
                    from = from,
                    to = to,
                    dayType = dayType,
                    typicalStartMin = circularMedianMinute(IntArray(trips.size) { t.startMin[trips[it]] }),
                    trips = trips.size,
                    lastMs = t.startMs[last],
                    medianDurationS = Math.round(
                        medianOf(DoubleArray(trips.size) { (t.endMs[trips[it]] - t.startMs[trips[it]]) / 1000.0 }),
                    ).toInt(),
                    medianDistanceKm = medianOf(DoubleArray(trips.size) { t.distanceKm[trips[it]] }),
                    hidden = match.hidden(from, to, dayType),
                    app = match.app(to),
                    lastTripId = t.id[last],
                ),
            )
        }
        out.sortWith(compareByDescending<Routine> { it.trips }.thenBy { it.id })
        return out
    }
}

/** Usable trips in start order with their places. Read-only once built. */
internal class TripTable(
    val id: LongArray,
    val startMs: LongArray,
    val endMs: LongArray,
    val distanceKm: DoubleArray,
    /** Local minute of day of the start. */
    val startMin: IntArray,
    /** Local day number of the start. */
    val day: LongArray,
    /** [DayType.ordinal] of the start. */
    val dayType: IntArray,
    val startPlace: IntArray,
    val endPlace: IntArray,
    /** Trips leaving each place for another one, at `place * 2 + dayType`, in start order. */
    val departures: Array<IntArray>,
) {
    val size: Int get() = id.size

    /** How many trips of [list] started before [ms]. */
    fun startedBefore(list: IntArray, ms: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (startMs[list[mid]] < ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY = TripTable(
            LongArray(0), LongArray(0), LongArray(0), DoubleArray(0), IntArray(0), LongArray(0), IntArray(0),
            IntArray(0), IntArray(0), emptyArray(),
        )
    }
}

/** Recency- and time-weighted vote over candidate trips. One per thread. */
private class Scorer(placeCount: Int, private val config: BriefingConfig) {
    private val weight = DoubleArray(placeCount)
    private val stamp = IntArray(placeCount)
    private val touched = IntArray(placeCount)
    private var round = 0
    var candidates = 0
        private set
    var share = 0.0
        private set

    /** The offered destination among the first [count] trips of [list], or -1. */
    fun score(t: TripTable, list: IntArray, count: Int, nowMs: Long, nowMin: Int): Int {
        round++
        var places = 0
        candidates = 0
        share = 0.0
        val window = config.windowMin
        val tau = config.recencyTauDays
        for (s in 0 until count) {
            val j = list[s]
            val gap = minuteGap(t.startMin[j], nowMin)
            if (gap > window) continue
            val ageDays = (nowMs - t.startMs[j]) / 1000.0 / 86400.0
            val w = exp(-ageDays / tau) * (1.0 - gap.toDouble() / (window + 1))
            val p = t.endPlace[j]
            if (stamp[p] != round) {
                stamp[p] = round
                weight[p] = 0.0
                touched[places++] = p
            }
            weight[p] += w
            candidates++
        }
        if (places == 0 || candidates < config.minMatches) return -1
        // First-seen destination wins a tie.
        var best = -1
        var top = 0.0
        var total = 0.0
        for (s in 0 until places) {
            val p = touched[s]
            val w = weight[p]
            total += w
            if (best < 0 || w > top) {
                best = p
                top = w
            }
        }
        share = top / total
        if (!(total > 0.0) || share < config.minShare || top < config.minWeight) return -1
        return if (distinctDays(t, list, count, nowMin, best) >= config.minDays) best else -1
    }

    // [list] is in start order, so one day's trips sit together.
    private fun distinctDays(t: TripTable, list: IntArray, count: Int, nowMin: Int, dest: Int): Int {
        var days = 0
        var lastDay = Long.MIN_VALUE
        for (s in 0 until count) {
            val j = list[s]
            if (t.endPlace[j] != dest || minuteGap(t.startMin[j], nowMin) > config.windowMin) continue
            if (t.day[j] != lastDay) {
                days++
                lastDay = t.day[j]
            }
        }
        return days
    }
}

/** Driver overrides matched to places by distance. */
internal class OverrideMatcher(private val overrides: BriefingOverrides, private val radiusM: Double) {

    fun hidden(from: BriefPlace, to: BriefPlace, dayType: DayType): Boolean =
        overrides.hidden.any { it.matches(from, to, dayType) }

    /** Preferred app for the nearest app choice within the radius of [to]. */
    fun app(to: BriefPlace): String? {
        var best: String? = null
        var bestD = Double.MAX_VALUE
        for (a in overrides.apps) {
            if (a.app.isBlank()) continue
            val d = haversineM(a.dLat, a.dLng, to.lat, to.lng)
            if (d <= radiusM && d < bestD) {
                best = a.app
                bestD = d
            }
        }
        return best
    }

    /** True when matching declines inside the window add up to [limit] or more. */
    fun declined(from: BriefPlace, to: BriefPlace, dayType: DayType, nowMs: Long, limit: Double, windowDays: Int): Boolean {
        val windowMs = windowDays * DAY_MS
        var any = false
        var count = 0.0
        for (d in overrides.declines) {
            if (!d.key.matches(from, to, dayType)) continue
            if (d.untilMs > nowMs || d.snoozeUntilMs > nowMs) return true
            if (nowMs - d.lastMs > windowMs) continue
            any = true
            count += d.count
        }
        return any && count >= limit
    }

    private fun RoutineKey.matches(from: BriefPlace, to: BriefPlace, dayType: DayType): Boolean =
        this.dayType == dayType &&
            haversineM(oLat, oLng, from.lat, from.lng) <= radiusM &&
            haversineM(dLat, dLng, to.lat, to.lng) <= radiusM
}
