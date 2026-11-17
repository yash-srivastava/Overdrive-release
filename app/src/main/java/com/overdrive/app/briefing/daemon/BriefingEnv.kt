package com.overdrive.app.briefing.daemon

import com.overdrive.app.briefing.engine.BriefTrip
import com.overdrive.app.briefing.engine.NamedPlace
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.ZoneId

internal interface BriefingLog {
    fun info(msg: String)
    fun warn(msg: String)
}

internal data class GpsFix(val lat: Double, val lng: Double, val ageMs: Long)

/** [rowsRead] counts every finished row read, usable or not; [storeCount] is what the store says it holds. */
internal class TripLoad(val trips: List<BriefTrip>, val rowsRead: Int, val storeCount: Int)

internal data class HazardPoint(val lat: Double, val lng: Double, val severity: Int, val firstSeenMs: Long)

/** Rain probability for one local clock hour. */
internal data class RainSlot(val hour: Int, val probabilityPct: Int)

/** Everything the service reads from the daemon. Calls may block briefly; none may throw. */
internal interface BriefingEnv : BriefingLog {
    fun nowMs(): Long

    /** Monotonic since boot. */
    fun elapsedMs(): Long
    fun zone(): ZoneId

    /** The locale's weekend days. */
    fun weekendDays(): Set<DayOfWeek>

    /** Changes on every head-unit boot; "" when unknown. */
    fun bootId(): String
    fun stateFile(): File
    fun settings(): TripBriefingSettings
    fun saveSettings(patch: JSONObject): Boolean

    /** Finished trips of the last two years; null while the trip store is not usable yet. */
    fun loadTrips(): TripLoad?

    /** Where the most recently finished trip ended, or null. */
    fun lastTripEnd(): LatLng?
    fun safeZones(): List<NamedPlace>
    fun gpsFix(): GpsFix?
    fun inReverse(): Boolean

    /** The live gear is known and is P. */
    fun inPark(): Boolean = !inReverse()

    /** The live gear as the launch gate needs it; UNKNOWN while the gear monitor is stale or not running. */
    fun gear(): BriefingGear = if (inReverse()) BriefingGear.REVERSE else BriefingGear.OTHER

    /** A gear read now where the car allows (else the latest fresh one) and when it was taken; null when unknown. */
    fun readGear(): GearRead? = gear().takeIf { it != BriefingGear.UNKNOWN }?.let { GearRead(it, elapsedMs()) }

    /** Since the gear reading last changed (or the monitor started), whether or not anyone was asking. */
    fun msSinceGearChange(): Long = Long.MAX_VALUE

    /** Since the car was last seen leaving R (0 while in R); MAX_VALUE when not seen in R. */
    fun msSinceReverse(): Long = Long.MAX_VALUE

    /** The gear for showing the card: a reading up to 2 s old still counts (a polled gear always is). */
    fun gearShown(): BriefingGear = gear()

    /** A deferred "navigate here" target that DeferredNavManager will prompt for at this ACC-on. */
    fun deferredNavPending(): Boolean

    /** Start of the trip in progress, or 0. */
    fun activeTripStartMs(): Long

    /** NaN when unknown. */
    fun vehicleSpeedKmh(): Double

    fun rangeKm(): Double?

    fun tripTrack(tripId: Long): List<LatLng>?
    fun roadSenseEnabled(): Boolean
    fun hazardsIn(box: GeoBox): List<HazardPoint>?

    /** Hourly rain probabilities from the current hour on, or null when unavailable. */
    fun rainSlots(at: LatLng): List<RainSlot>?
}

internal enum class BriefingGear { REVERSE, OTHER, UNKNOWN }

/** [atMs]: when the reading was taken at its source ([BriefingEnv.elapsedMs] clock). */
internal data class GearRead(val gear: BriefingGear, val atMs: Long)
