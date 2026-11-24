package com.overdrive.app.briefing.daemon

import com.overdrive.app.briefing.engine.BriefTrip
import com.overdrive.app.briefing.engine.DEFAULT_WEEKEND
import com.overdrive.app.briefing.engine.NamedPlace
import com.overdrive.app.briefing.engine.PlaceSource
import com.overdrive.app.byd.BydDataCollector
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.daemon.CameraDaemon
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.monitor.GearMonitor
import com.overdrive.app.monitor.GpsMonitor
import com.overdrive.app.monitor.VehicleDataMonitor
import com.overdrive.app.roadsense.store.RoadSenseStore
import com.overdrive.app.storage.removable.RemovableStorageFs
import com.overdrive.app.surveillance.SafeLocationManager
import com.overdrive.app.telenav.DeferredNavManager
import com.overdrive.app.trips.TelemetryStore
import com.overdrive.app.trips.TripApiHandler
import com.overdrive.app.trips.TripDatabase
import com.overdrive.app.trips.TripRecord
import com.overdrive.app.util.ScratchRoot
import com.overdrive.app.weather.WeatherTemperature
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.ZoneId

/** The daemon's real sources. Every call degrades to "unknown" instead of throwing. */
internal object DaemonBriefingEnv : BriefingEnv {

    private const val HISTORY_DAYS = 730
    private const val PAGE = 500
    private const val MAX_ROWS = 20_000
    private const val MAX_TRACK_FILE_BYTES = 8L shl 20
    private const val MAX_HAZARDS = 2_000
    private const val FRESH_SPEED_MS = 5_000L
    private const val GEAR_SHOWN_MAX_AGE_MS = 2_000L

    private val logger = DaemonLogger.getInstance("TripBriefing")

    override fun info(msg: String) = logger.info(msg)

    override fun warn(msg: String) = logger.warn(msg)

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun elapsedMs(): Long = android.os.SystemClock.elapsedRealtime()

    override fun zone(): ZoneId = ZoneId.systemDefault()

    override fun weekendDays(): Set<DayOfWeek> = try {
        val w = android.icu.util.Calendar.getInstance(android.icu.util.ULocale.getDefault()).weekData
        // ICU numbers days Sunday = 1 … Saturday = 7; the weekend may wrap past Saturday.
        val out = java.util.EnumSet.noneOf(DayOfWeek::class.java)
        var d = w.weekendOnset
        for (i in 0 until 7) {
            out.add(DayOfWeek.of(((d + 5) % 7) + 1))
            if (d == w.weekendCease) break
            d = d % 7 + 1
        }
        if (out.isEmpty() || out.size > 3) DEFAULT_WEEKEND else out
    } catch (t: Throwable) {
        DEFAULT_WEEKEND
    }

    override fun bootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim().take(64)
    } catch (t: Throwable) {
        ""
    }

    override fun stateFile(): File = File(ScratchRoot.path("trip_briefing.json"))

    override fun settings(): TripBriefingSettings = TripBriefingSettings.load()

    override fun saveSettings(patch: JSONObject): Boolean = TripBriefingSettings.save(patch)

    override fun loadTrips(): TripLoad? = try {
        val tam = CameraDaemon.getTripAnalyticsManager()
        when {
            tam == null || !tam.isInitialized -> null
            // Trip analytics switched off: nothing to learn from.
            !tam.isEnabled -> TripLoad(emptyList(), 0, 0)
            else -> readTrips(tam.database)
        }
    } catch (t: Throwable) {
        warn("trip load failed: ${t.javaClass.simpleName}")
        null
    }

    private fun readTrips(db: TripDatabase?): TripLoad? {
        if (db == null || !db.isAvailable) return null
        val out = ArrayList<BriefTrip>()
        val seen = HashSet<Long>()
        var rows = 0
        while (rows < MAX_ROWS) {
            val page = db.getTripsWithoutMoments(HISTORY_DAYS, PAGE, rows)
            for (r in page) {
                if (seen.add(r.id)) toBrief(r)?.let { out.add(it) }
            }
            rows += page.size
            if (page.size < PAGE) break
        }
        if (!db.isAvailable) return null
        return TripLoad(out, rows, db.tripCount)
    }

    private fun toBrief(r: TripRecord): BriefTrip? {
        if (r.startTime <= 0L || r.endTime < r.startTime) return null
        if (!BriefingGeo.usable(r.startLat, r.startLon) || !BriefingGeo.usable(r.endLat, r.endLon)) return null
        val km = if (r.distanceKm.isFinite() && r.distanceKm > 0.0) r.distanceKm else 0.0
        return BriefTrip(r.id, r.startTime, r.endTime, r.startLat, r.startLon, r.endLat, r.endLon, km)
    }

    override fun lastTripEnd(): LatLng? = try {
        val db = CameraDaemon.getTripAnalyticsManager()?.database
        val last = if (db != null && db.isAvailable) db.getTripsWithoutMoments(HISTORY_DAYS, 1, 0).firstOrNull() else null
        last?.takeIf { BriefingGeo.usable(it.endLat, it.endLon) }?.let { LatLng(it.endLat, it.endLon) }
    } catch (t: Throwable) {
        null
    }

    override fun safeZones(): List<NamedPlace> = try {
        SafeLocationManager.getInstance().geometrySnapshot.mapNotNull { z ->
            val name = BriefingJson.cleanLabel(z.name)
            if (name == null || !z.geometryValid || !BriefingGeo.usable(z.latitude, z.longitude)) null
            else NamedPlace(name, z.latitude, z.longitude, PlaceSource.SAFE)
        }
    } catch (t: Throwable) {
        emptyList()
    }

    override fun gpsFix(): GpsFix? = try {
        val f = GpsMonitor.getInstance().fixSnapshot
        if (f == null || !f.hasLocation()) null else GpsFix(f.latitude, f.longitude, GpsMonitor.fixAgeMs(f))
    } catch (t: Throwable) {
        null
    }

    // a monitor that isn't running (yet: it starts late in ACC-on) knows nothing, so nothing may launch or show
    override fun gear(): BriefingGear = try {
        when (readRaw()) {
            com.overdrive.app.byd.BydVehicleData.UNAVAILABLE -> BriefingGear.UNKNOWN
            GearMonitor.GEAR_R -> BriefingGear.REVERSE
            else -> BriefingGear.OTHER
        }
    } catch (t: Throwable) {
        BriefingGear.UNKNOWN
    }

    // The fresh gear now (UNAVAILABLE: unknown). A known one is kept as the last reading; reading and keeping under one
    // lock, so a slower thread can't put an older reading back over a newer one.
    private fun readRaw(): Int = synchronized(rawLock) {
        val g = GearMonitor.getInstance()
        val raw = if (g.isRunning) g.currentGearIfFresh else com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
        if (raw != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE) lastRaw = RawGear(raw, android.os.SystemClock.elapsedRealtime())
        raw
    }

    // a gear reading and when it was taken, kept together
    private class RawGear(val gear: Int, val atMs: Long)
    @Volatile private var lastRaw = RawGear(com.overdrive.app.byd.BydVehicleData.UNAVAILABLE, 0L)
    private val rawLock = Any()

    // the last reading, if taken within the last 2 s
    private fun recentRaw(): Int = lastRaw.let {
        if (it.gear != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE &&
            android.os.SystemClock.elapsedRealtime() - it.atMs <= GEAR_SHOWN_MAX_AGE_MS) it.gear
        else com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
    }

    // Every gear change GearMonitor sees (CameraDaemon.onGearChanged), so the launch gate knows what came before it.
    @Volatile private var gearChangedAt = 0L
    @Volatile private var inR = false
    @Volatile private var leftRAt = 0L

    @JvmStatic
    fun onGear(gear: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val r = gear == GearMonitor.GEAR_R
        if (inR && !r) leftRAt = now
        inR = r
        gearChangedAt = now
    }

    override fun readGear(): GearRead? = try {
        GearMonitor.getInstance().readNow()?.let {
            GearRead(if (it.gear == GearMonitor.GEAR_R) BriefingGear.REVERSE else BriefingGear.OTHER, it.observedAtMs)
        }
    } catch (t: Throwable) {
        null
    }

    override fun msSinceGearChange(): Long =
        gearChangedAt.let { if (it <= 0L) 0L else android.os.SystemClock.elapsedRealtime() - it }

    override fun msSinceReverse(): Long = when {
        inR -> 0L
        leftRAt <= 0L -> Long.MAX_VALUE
        else -> android.os.SystemClock.elapsedRealtime() - leftRAt
    }

    // DiLink5's reading goes stale for a moment every probe cycle: the card shouldn't blink for it.
    override fun gearShown(): BriefingGear {
        val now = gear()
        if (now != BriefingGear.UNKNOWN) return now
        return when (recentRaw()) {
            com.overdrive.app.byd.BydVehicleData.UNAVAILABLE -> BriefingGear.UNKNOWN
            GearMonitor.GEAR_R -> BriefingGear.REVERSE
            else -> BriefingGear.OTHER
        }
    }

    // (a reading stale for a moment, as on DiLink5 between probes, keeps the last one up to 2 s)
    override fun inPark(): Boolean = try {
        val g = readRaw().let { if (it == com.overdrive.app.byd.BydVehicleData.UNAVAILABLE) recentRaw() else it }
        g == GearMonitor.GEAR_P
    } catch (t: Throwable) {
        false
    }

    override fun inReverse(): Boolean = try {
        GearMonitor.getInstance().currentGear == GearMonitor.GEAR_R
    } catch (t: Throwable) {
        false
    }

    override fun deferredNavPending(): Boolean = DeferredNavManager.hasPendingTarget()

    override fun activeTripStartMs(): Long = try {
        val tam = CameraDaemon.getTripAnalyticsManager()
        if (tam != null && tam.isTripActive) tam.activeTrip?.startTime ?: 0L else 0L
    } catch (t: Throwable) {
        0L
    }

    override fun vehicleSpeedKmh(): Double {
        val car = try {
            val c = BydDataCollector.getInstance()
            if (c != null && c.isInitialized) c.data?.speedKmh ?: Double.NaN else Double.NaN
        } catch (t: Throwable) {
            Double.NaN
        }
        if (car.isFinite()) return car
        // No car speed yet (the collector still starting): a fresh GPS fix's.
        return try {
            val f = GpsMonitor.getInstance().fixSnapshot
            val age = GpsMonitor.fixAgeMs(f)
            if (age in 0L..FRESH_SPEED_MS) f.speed * 3.6 else Double.NaN
        } catch (t: Throwable) {
            Double.NaN
        }
    }

    /** Learned estimate on a BEV; on a PHEV the car's combined electric + fuel range. */
    override fun rangeKm(): Double? = try {
        val vdm = VehicleDataMonitor.getInstance()
        val phev = vdm.isPhev
        val learned = if (phev) null else learnedRangeKm()
        learned ?: vdm.drivingRange?.let { r ->
            val km = if (phev) r.totalRangeKm else r.elecRangeKm
            if (km > 0) km.toDouble() else null
        }
    } catch (t: Throwable) {
        null
    }

    private fun learnedRangeKm(): Double? {
        val tam = CameraDaemon.getTripAnalyticsManager() ?: return null
        val json = TripApiHandler(tam).handleRequest("/api/trips/range", "GET", null, null) ?: return null
        if (json.optInt("_status", 200) != 200) return null
        val km = json.optJSONObject("range")?.optDouble("predictedRangeKm", Double.NaN) ?: return null
        return km.takeIf { it.isFinite() && it > 0.0 }
    }

    override fun tripTrack(tripId: Long): List<LatLng>? = try {
        val rec = CameraDaemon.getTripAnalyticsManager()?.database?.getTripWithoutMoments(tripId)
        val path = rec?.telemetryFilePath
        val file = if (path.isNullOrEmpty() || TripDatabase.isImportedPath(path)) null else File(path)
        // A long drive's 5 Hz file decodes to tens of MB at car start; skip it rather than stall.
        if (file == null || !RemovableStorageFs.isFile(file) || RemovableStorageFs.length(file) > MAX_TRACK_FILE_BYTES) {
            null
        } else {
            TelemetryStore.readFromFile(file).mapNotNull { s ->
                if (BriefingGeo.usable(s.lat, s.lon)) LatLng(s.lat, s.lon) else null
            }.takeIf { it.size >= 2 }
        }
    } catch (t: Throwable) {
        null
    }

    override fun roadSenseEnabled(): Boolean = try {
        UnifiedConfigManager.loadConfig().optJSONObject("roadSense")?.optBoolean("enabled", false) == true
    } catch (t: Throwable) {
        false
    }

    override fun hazardsIn(box: GeoBox): List<HazardPoint>? = try {
        RoadSenseStore.getInstance()
            .queryByBbox(box.minLat, box.minLng, box.maxLat, box.maxLng, MAX_HAZARDS)
            .map { HazardPoint(it.hazard.lat, it.hazard.lng, it.hazard.severity.level, it.createdMs) }
    } catch (t: Throwable) {
        null
    }

    // fetchNow serves its 10 min cache, otherwise one bounded Open-Meteo call.
    override fun rainSlots(at: LatLng): List<RainSlot>? = try {
        WeatherTemperature.fetchNow(at.lat, at.lng)
        val slots = WeatherTemperature.getCachedPrecipSlots().map { RainSlot(it[0], it[1]) }
        // The cache can be up to an hour old (and only covers today): start at the current hour, or say nothing.
        val hour = java.time.LocalTime.now().hour
        val from = slots.indexOfFirst { it.hour == hour }
        if (from < 0) null else slots.drop(from).takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        null
    }
}
