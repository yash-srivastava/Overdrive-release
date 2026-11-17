package com.overdrive.app.briefing.engine

import java.time.DayOfWeek

/** One recorded trip, as the engine needs it (epoch ms, WGS84). */
data class BriefTrip(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val distanceKm: Double,
)

enum class PlaceSource { USER, SAVED_HOME, SAVED_WORK, SAVED, SAFE, RECENT }

enum class PlaceRole { HOME, WORK }

enum class DayType { WEEKDAY, WEEKEND }

/** A name the driver or the app already knows for a location. */
data class NamedPlace(val name: String, val lat: Double, val lng: Double, val source: PlaceSource)

/** A clustered trip endpoint. [label] is null when no source names it. */
data class BriefPlace(
    val lat: Double,
    val lng: Double,
    val label: String?,
    val role: PlaceRole?,
    /** Trip starts plus trip ends in this place. */
    val visits: Int,
)

/** Driver choices that survive model rebuilds; matched by distance, not id. */
data class BriefingOverrides(
    val hidden: List<RoutineKey> = emptyList(),
    val apps: List<AppChoice> = emptyList(),
    val declines: List<Decline> = emptyList(),
)

data class RoutineKey(val oLat: Double, val oLng: Double, val dLat: Double, val dLng: Double, val dayType: DayType)

data class AppChoice(val dLat: Double, val dLng: Double, val app: String)

/**
 * [untilMs]: the routine stays quiet until then after repeated declines; [rounds] such pauses so far.
 * [snoozeUntilMs]: a single "no" keeps it quiet for a few hours (not a pause).
 */
data class Decline(
    val key: RoutineKey,
    val count: Double,
    val lastMs: Long,
    val untilMs: Long = 0L,
    val rounds: Int = 0,
    val snoozeUntilMs: Long = 0L,
)

data class Routine(
    val id: String,
    val from: BriefPlace,
    val to: BriefPlace,
    val dayType: DayType,
    /** Typical start, minutes after local midnight. */
    val typicalStartMin: Int,
    val trips: Int,
    /** Start of the most recent trip. */
    val lastMs: Long,
    val medianDurationS: Int,
    val medianDistanceKm: Double,
    val hidden: Boolean,
    val app: String?,
    /** [BriefTrip.id] of the most recent trip. */
    val lastTripId: Long = -1L,
)

data class Offer(
    val routineId: String,
    val dest: BriefPlace,
    val dayType: DayType,
    /** Top destination's weight share among the matches, 0..1. */
    val share: Double,
    /** Past trips that matched (origin, time window, day type), to any destination. */
    val matches: Int,
    /** Medians over the matches that went to [dest]. */
    val medianDurationS: Int,
    val medianDistanceKm: Double,
    val app: String?,
    /** [BriefTrip.id] of the most recent match that went to [dest]. */
    val lastTripId: Long = -1L,
    /** Place the origin snapped to; with [dest] and [dayType] it forms the routine's [RoutineKey]. */
    val from: BriefPlace? = null,
)

data class RoutineScore(val routineId: String, val offers: Int, val correct: Int)

data class SelfTest(
    val trips: Int,
    val offers: Int,
    val correct: Int,
    val routines: List<RoutineScore>,
    /** Days from the first trip to the first offer the replay would have made; null if none. */
    val firstOfferDay: Int?,
)

data class BriefingConfig(
    val radiusM: Double = 400.0,
    val windowMin: Int = 60,
    val minMatches: Int = 3,
    val minShare: Double = 0.7,
    /** Distinct days behind an offer or a routine: one odd day of trips (a move, a hospital day) isn't a habit. */
    val minDays: Int = 3,
    /** Recency weight the offered destination needs: old habits alone don't speak. */
    val minWeight: Double = 1.0,
    val recencyTauDays: Double = 90.0,
    val routineMinTrips: Int = 3,
    val declineLimit: Double = 2.0,
    val declineWindowDays: Int = 14,
    /** Days that count as the weekend; the locale's (Fri–Sat in much of the Middle East). */
    val weekendDays: Set<DayOfWeek> = DEFAULT_WEEKEND,
)

val DEFAULT_WEEKEND: Set<DayOfWeek> = java.util.EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
