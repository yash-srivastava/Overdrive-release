package com.overdrive.app.overlay

import com.overdrive.app.overlay.glass.GlassBatteryView
import com.overdrive.app.overlay.glass.GlassChargingView
import com.overdrive.app.overlay.glass.GlassControlsView
import com.overdrive.app.overlay.glass.GlassDriveView
import com.overdrive.app.overlay.glass.GlassPickerSheet
import com.overdrive.app.overlay.glass.GlassPowerView
import com.overdrive.app.overlay.glass.GlassRoadSenseView
import com.overdrive.app.overlay.glass.GlassSeatsView
import com.overdrive.app.overlay.glass.GlassTripView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Maps core's launcher summary, vehicle state and RoadSense overlay state onto the glass widgets. No views. */
internal object GlassWidgetsFeed {

    /** One summary poll, already shaped for the widgets. [frontRpm]/[rearRpm] feed motor auto-detection. */
    class Snapshot(
        val driving: Boolean,
        val reversing: Boolean,
        val charging: Boolean,
        val socPct: Int?,
        val battery: GlassBatteryView.State?,
        val chargingState: GlassChargingView.State,
        val trip: GlassTripView.State?,
        val tripTopKmh: Int?,
        val roadActive: Boolean,
        val keepLeft: Boolean,
        val phev: Boolean,
        val packKw: Float?,
        val engineKw: Float,
        val frontRpm: Float?,
        val rearRpm: Float?,
        val bsLeft: Int,
        val bsRight: Int,
        /** A vehicle in a blind spot, side not reported (DiLink 3.0's BSD state). */
        val bsAny: Int = 0,
        val tyres: FloatArray?,
        val tyreStates: List<String?>?,
        val loBar: Float,
        val hiBar: Float,
        val locked: Boolean?,
        /** Altitude, grade and the elevation behind the car, while driving. */
        val terrain: GlassRoadSenseView.Terrain? = null,
        /** The driver door alone; picks the lock tile's action, never shown as whole-car state. */
        val driverLocked: Boolean? = null,
        /** While charging: what this charge has added and cost so far; null when not charging. */
        val chargeCost: ChargeCost? = null,
        /** Core reads the car as switched off: neither on nor charging. */
        val carOff: Boolean = false,
        /** A DiLink 3/4 plug-in hybrid's drive power (kW, − regen); custom widgets only. */
        val driveKw: Float? = null,
    )

    /** A charge's energy added and cost so far: the session's own figures, else live energy at the live rate ([costEstimated]). */
    class ChargeCost(val addedKwh: Float?, val cost: Float?, val currency: String?, val costEstimated: Boolean)

    /** [knownPhev] is the powertrain from an earlier summary, kept when this one leaves the vehicle block out. */
    fun parse(s: JSONObject, knownPhev: Boolean? = null): Snapshot {
        val st = s.optJSONObject("state") ?: JSONObject()
        val r = s.optJSONObject("range") ?: JSONObject()
        val b = s.optJSONObject("battery") ?: JSONObject()
        val c = s.optJSONObject("charging") ?: JSONObject()
        val v = s.optJSONObject("vehicle") ?: JSONObject()
        val rs = s.optJSONObject("roadsense") ?: JSONObject()
        val p = s.optJSONObject("power")
        val gear = st.optString("gear").takeIf { !st.isNull("gear") && it.isNotEmpty() }
        // with no gear reading, turning motors still mean the car is moving: the park-only parts stay shut
        val driving = (st.has("parked") && !st.optBoolean("parked", true) && gear != null && gear != "P") ||
            (gear == null && (motorSeen(p?.let { num(it, "frontRpm") }) || motorSeen(p?.let { num(it, "rearRpm") })))
        val charging = c.optBoolean("active", false)
        val soc = num(r, "socPct") ?: num(b, "socPct")
        val socI = soc?.roundToInt()?.coerceIn(0, 100)
        val phev = if (v.has("isPhev") && !v.isNull("isPhev")) v.optBoolean("isPhev") else knownPhev ?: false

        val battery = socI?.let {
            GlassBatteryView.State(it, num(r, "totalKm")?.roundToInt(), phev, num(r, "fuelPct")?.roundToInt())
        }
        val chargingState = GlassChargingView.State(
            active = charging, socPct = socI ?: 0, targetPct = num(c, "targetPct")?.roundToInt(), etaMin = num(c, "etaMin")?.roundToInt(),
            kw = num(c, "kw") ?: 0f, kwEstimated = c.optBoolean("kwEstimated", false), dc = null, socKnown = socI != null,
        )
        val trip = s.optJSONObject("trip")?.let { t ->
            GlassTripView.State(
                num(t, "distanceKm") ?: 0f, num(t, "durationMin") ?: 0f, num(t, "whPerKm")?.roundToInt(), t.optString("phase") == "live",
                avgKmh = num(t, "avgKmh"), topKmh = num(t, "maxKmh")?.roundToInt()?.takeIf { it > 0 }, kwh = num(t, "kwh"),
                socStart = num(t, "socStart")?.roundToInt(), socEnd = num(t, "socEnd")?.roundToInt(),
                // null: a hybrid whose engine use this trip wasn't captured
                engine = if (!t.has("engine")) false else if (t.isNull("engine")) null else t.optBoolean("engine", false),
                fuelL = num(t, "fuelL"),
                cost = num(t, "cost"), currency = t.optString("currency").takeIf { !t.isNull("currency") && it.isNotEmpty() },
                // the 30-day average stands in for an unscored trip; only the trip's own score is shown
                score = t.optJSONObject("dna")?.takeIf { t.has("dnaAvg") && !t.optBoolean("dnaAvg", true) }
                    ?.let { num(it, "overall") }?.roundToInt()?.takeIf { it > 0 },
            )
        }
        val ty = s.optJSONObject("tyres")
        val tyres = ty?.let { floatArrayOf(barOf(it, "flBar"), barOf(it, "frBar"), barOf(it, "rlBar"), barOf(it, "rrBar")) }
        val bs = s.optJSONObject("blindSpot")
        return Snapshot(
            driving = driving,
            reversing = gear == "R",
            charging = charging,
            socPct = socI,
            battery = battery,
            chargingState = chargingState,
            trip = trip,
            tripTopKmh = s.optJSONObject("trip")?.let { num(it, "maxKmh") }?.roundToInt()?.takeIf { it > 0 },
            roadActive = rs.optBoolean("active", false),
            // right-hand drive cars keep left, so the signs stand on the left kerb
            keepLeft = v.optString("driveSide", "rhd") == "rhd",
            phev = phev,
            packKw = p?.let { num(it, "packKw") },
            engineKw = p?.let { num(it, "engineKw") } ?: 0f,
            frontRpm = p?.let { num(it, "frontRpm") },
            rearRpm = p?.let { num(it, "rearRpm") },
            bsLeft = bs?.optInt("left", 0) ?: 0,
            bsRight = bs?.optInt("right", 0) ?: 0,
            bsAny = bs?.optInt("any", 0) ?: 0,
            tyres = tyres,
            tyreStates = ty?.let { o -> listOf("flState", "frState", "rlState", "rrState").map { k -> o.optString(k, "").takeIf { !o.isNull(k) && it.isNotEmpty() } } }
                ?.takeIf { l -> l.any { it != null } },
            loBar = ty?.let { num(it, "loBar") } ?: 2.34f,
            hiBar = ty?.let { num(it, "hiBar") } ?: 3.10f,
            locked = if (st.has("locked") && !st.isNull("locked")) st.optBoolean("locked") else null,
            driverLocked = if (st.has("driverLocked") && !st.isNull("driverLocked")) st.optBoolean("driverLocked") else null,
            terrain = terrain(s.optJSONObject("terrain")),
            chargeCost = if (!charging) null else ChargeCost(num(c, "addedKwh"), num(c, "cost"),
                c.optString("currency").trim().take(8).takeIf { !c.isNull("currency") && it.isNotEmpty() }, c.optBoolean("costEstimated", false)),
            carOff = st.optString("acc") == "off",
            driveKw = p?.let { num(it, "driveKw") },
        )
    }

    /** The power widget's state for [s]; [drive] is the configured or detected motor layout, [bs] a fresher blind-spot reading. */
    fun power(s: Snapshot, drive: GlassPowerView.Drive, showBs: Boolean, bs: Live? = null): GlassPowerView.State {
        val t = s.tyres
        return GlassPowerView.State(
            phase = if (s.charging) GlassPowerView.Phase.CHARGING else if (s.driving) GlassPowerView.Phase.DRIVING else GlassPowerView.Phase.PARKED,
            // while charging, core's placeholder rate, or the 0 it sends with no reading, is no figure: a dash, never "0.0 kW"
            kw = if (s.charging) s.chargingState.let { if (it.kwEstimated || it.kw <= 0f) null else it.kw } else s.packKw,
            drive = drive, phev = s.phev, engineKw = s.engineKw, soc = (s.socPct ?: 50).toFloat(),
            showBs = showBs, bsLeft = bs?.bsLeft ?: s.bsLeft, bsRight = bs?.bsRight ?: s.bsRight, bsAny = bs?.bsAny ?: s.bsAny,
            fl = t?.get(0)?.takeIf { !it.isNaN() }, fr = t?.get(1)?.takeIf { !it.isNaN() },
            rl = t?.get(2)?.takeIf { !it.isNaN() }, rr = t?.get(3)?.takeIf { !it.isNaN() },
            loBar = s.loBar, hiBar = s.hiBar, tyreStates = s.tyreStates,
        )
    }

    /**
     * OverDrive's learned range from `/api/trips/range`, or null until it has learned enough trips.
     * A hybrid's is the combined figure, and only counts as calibrated once one of its legs is learned.
     */
    fun calibrated(o: JSONObject, phev: Boolean): Int? {
        val ev = o.optJSONObject("range")?.let { num(it, "predictedRangeKm") }?.takeIf { it > 0f }
        if (!phev) return ev?.roundToInt()
        val fuel = o.optJSONObject("fuelRange")?.let { num(it, "predictedRangeKm") }?.takeIf { it > 0f }
        if (ev == null && fuel == null) return null
        return num(o, "totalRangeKm")?.takeIf { it > 0f }?.roundToInt()
    }

    /** The live drive widget's totals; [regenPct] comes from the overlay's own samples. */
    fun drive(s: Snapshot, regenPct: Int?): GlassDriveView.Trip? = s.trip?.let { t ->
        GlassDriveView.Trip(t.live, t.km, t.minutes.roundToInt(), t.whPerKm, s.tripTopKmh, if (t.live) regenPct else null)
    }

    /** One `/api/widgets/live` reading; NaN where the car didn't report it, and the blind spot when sent. [driveKw] is as in [Snapshot]. */
    class Live(
        val kw: Float, val kmh: Float, val accel: Float, val brake: Float, val frontRpm: Float, val rearRpm: Float,
        val hasBs: Boolean, val bsLeft: Int, val bsRight: Int, val bsAny: Int, val driveKw: Float = Float.NaN,
    ) {
        /** The busier motor's speed (the front reads negated in reverse). */
        val rpm: Float get() = if (frontRpm.isNaN()) abs(rearRpm) else if (rearRpm.isNaN()) abs(frontRpm) else maxOf(abs(frontRpm), abs(rearRpm))
    }

    fun live(o: JSONObject): Live {
        val bs = o.optJSONObject("blindSpot")
        return Live(
            num(o, "packKw") ?: Float.NaN, num(o, "speedKmh") ?: Float.NaN,
            num(o, "accelPct") ?: Float.NaN, num(o, "brakePct") ?: Float.NaN,
            num(o, "frontRpm") ?: Float.NaN, num(o, "rearRpm") ?: Float.NaN,
            bs != null, bs?.optInt("left", 0) ?: 0, bs?.optInt("right", 0) ?: 0, bs?.optInt("any", 0) ?: 0,
            num(o, "driveKw") ?: Float.NaN,
        )
    }

    /** Core answers some failures with 200 and {"success": false}; such a reply carries no data. */
    fun failed(o: JSONObject) = o.has("success") && !o.optBoolean("success", true)

    /** `/api/widgets/automations` as chooser rows; null for a failed reply, never an empty list standing in for one. */
    fun automations(o: JSONObject): List<GlassPickerSheet.Item>? {
        if (failed(o)) return null
        val a = o.optJSONArray("automations") ?: return null
        val out = ArrayList<GlassPickerSheet.Item>(a.length())
        for (i in 0 until a.length()) {
            val x = a.optJSONObject(i) ?: continue
            val id = x.optString("id").takeIf { it.isNotEmpty() } ?: continue
            out.add(GlassPickerSheet.Item(id, x.optString("name"), x.optString("mode", "automatic"), x.optString("glyph", "bolt")))
        }
        return out
    }

    /** `/api/positions`: the saved seat positions, and whether the car lets one be applied now ([reason] says why not). */
    class Seats(val entries: List<GlassSeatsView.Entry>, val blocked: Boolean, val reason: String?)

    /** Null for a failed reply, never an empty list standing in for one. */
    fun seats(o: JSONObject): Seats? {
        if (failed(o)) return null
        val a = o.optJSONArray("positions") ?: return null
        val entries = ArrayList<GlassSeatsView.Entry>(a.length())
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            val id = p.optString("id").takeIf { it.isNotEmpty() && !p.isNull("id") } ?: continue
            entries.add(GlassSeatsView.Entry(id, p.optString("name").trim().ifEmpty { "Position ${entries.size + 1}" }))
        }
        // as the Seat Positions page gates its buttons: the motors are unpowered with the car off, and the
        // positioning guard holds them outside Park
        val off = o.has("acc") && !o.optBoolean("acc", true)
        val guarded = o.optBoolean("positioningBlocked", false)
        // the guard's own code goes through as it is; the widget words it
        val reason = when {
            off -> "Turn the car on first"
            guarded -> o.optString("movementBlockReason").takeIf { !o.isNull("movementBlockReason") && it.isNotEmpty() }
            else -> null
        }
        return Seats(entries, off || guarded, reason)
    }

    /** A `/api/positions/apply` answer as a short line for the widget; null when the seat moved. */
    fun seatFailure(code: Int, text: String): String? {
        if (code <= 0) return "Couldn't reach the car"
        if (code !in 200..299) return "Seat didn't move"
        val o = try { JSONObject(text) } catch (_: Throwable) { return "Seat didn't move" }
        if (o.optBoolean("needsModelAck", false)) return "Confirm on the Seat Positions page"
        return if (o.has("error") || o.has("skipped") || failed(o)) "Seat didn't move" else null
    }

    /** One channel of `/api/widgets/mqtt`: a finite number, else short plain text, else null. */
    fun mqttValue(values: JSONObject, channel: String): Any? {
        if (!values.has(channel) || values.isNull(channel)) return null
        return when (val v = values.opt(channel)) {
            is Number -> v.toDouble().takeIf { it.isFinite() }
            is String -> v.trim().let { t -> t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: t.filter { !it.isISOControl() }.take(64) }
            else -> null
        }
    }

    /** A motor counts as present once it has turned faster than walking pace while driving. */
    fun motorSeen(rpm: Float?) = rpm != null && abs(rpm) > 60f

    /** Lock, climate, cabin temperature and windows from `/api/vehicle/state`; null fields were not reported. */
    class Vehicle(val locked: Boolean?, val climate: Boolean?, val cabinC: Int?, val windows: GlassControlsView.Windows?)

    fun vehicle(o: JSONObject): Vehicle {
        val clim = o.optJSONObject("climate")
        val w = o.optJSONObject("windows")
        // a window known open without a percentage comes as -1 here and true in windowOpen
        val open = o.optJSONObject("windowOpen")
        var max = -1f
        var openBy = false
        if (w != null) for (k in arrayOf("lf", "rf", "lr", "rr")) {
            val x = num(w, k)
            if (x != null && x >= 0f) max = maxOf(max, x)
            else if (open != null && open.optBoolean(k, false)) openBy = true
        }
        // only a whole-vehicle reading counts as the lock state; a driver-door-only one is partial
        val doors = o.optJSONObject("doors")
        val overall = doors?.takeIf { it.optString("scope") == "vehicle" }?.optInt("overall", -1) ?: -1
        return Vehicle(
            locked = when (overall) { 1 -> true; 2 -> false; else -> null },
            climate = clim?.takeIf { it.has("acOn") && !it.isNull("acOn") }?.optBoolean("acOn"),
            cabinC = clim?.let { num(it, "insideTempC") }?.roundToInt(),
            windows = when {
                openBy -> GlassControlsView.Windows.OPEN
                max < 0f -> null
                max <= 2f -> GlassControlsView.Windows.CLOSED
                max <= 25f -> GlassControlsView.Windows.VENT
                else -> GlassControlsView.Windows.OPEN
            },
        )
    }

    /** The RoadSense widget's hazard fields from the daemon's live overlay state, published every warn tick while driving. */
    class Road(val feed: Boolean, val clear: Boolean, val kind: GlassRoadSenseView.Kind, val severity: Int, val distanceM: Float, val cluster: Int)

    // a profile needs two readings to draw; an altitude without one isn't shown
    private fun terrain(t: JSONObject?): GlassRoadSenseView.Terrain? {
        t ?: return null
        val alt = num(t, "altM") ?: return null
        val arr = t.optJSONArray("profile") ?: return null
        val profile = (0 until arr.length()).mapNotNull { i -> arr.optDouble(i, Double.NaN).takeIf { !it.isNaN() }?.toFloat() }
        if (profile.size < 2) return null
        return GlassRoadSenseView.Terrain(alt.roundToInt(), t.optBoolean("msl", false), num(t, "gradePct"), profile,
            num(t, "distM") ?: 0f, (num(t, "stepM") ?: 25f).coerceAtLeast(1f))
    }

    fun road(o: JSONObject?, now: Long): Road {
        val fresh = o != null && abs(now - o.optLong("ts", 0L)) < ROAD_FRESH_MS
        if (o == null || !fresh) return Road(false, true, GlassRoadSenseView.Kind.BREAKER, 1, 0f, 1)
        val kind = when (o.optInt("type", 2)) {
            0 -> GlassRoadSenseView.Kind.BREAKER
            1 -> GlassRoadSenseView.Kind.POTHOLE
            3 -> GlassRoadSenseView.Kind.ROUGH
            else -> GlassRoadSenseView.Kind.UNKNOWN
        }
        return Road(
            feed = true,
            clear = !o.optBoolean("ahead", false),
            kind = kind,
            severity = o.optInt("sev", 1).coerceIn(1, 3),
            distanceM = o.optDouble("m", 0.0).toFloat().coerceAtLeast(0f),
            cluster = o.optInt("zc", 1).coerceIn(1, 4),
        )
    }

    private fun num(o: JSONObject, k: String): Float? {
        if (!o.has(k) || o.isNull(k)) return null
        val d = o.optDouble(k, Double.NaN)
        return if (d.isNaN()) null else d.toFloat()
    }

    private fun barOf(o: JSONObject, k: String) = num(o, k) ?: Float.NaN

    /** The daemon's heartbeat is 3 s; allow one missed beat before calling the feed gone. */
    private const val ROAD_FRESH_MS = 6_000L
}
