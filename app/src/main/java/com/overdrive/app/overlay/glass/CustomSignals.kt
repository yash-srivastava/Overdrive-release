package com.overdrive.app.overlay.glass

import com.overdrive.app.overlay.GlassWidgetsFeed
import org.json.JSONArray
import org.json.JSONObject

/**
 * The car data a user widget can read: only what the overlay already receives (the launcher summary,
 * RoadSense's state and the live poll). The catalog is the builder's list; [values] maps one reading.
 * Numbers are Doubles, unknowns null. The catalog side is pure data and also runs in the daemon.
 */
object CustomSignals {
    private class Sig(
        val key: String, val group: String, val label: String, val unit: String, val type: String,
        val min: Double, val max: Double, val step: Double, val def: Any?, val live: Boolean, val options: List<Pair<Int, String>>?,
    )

    private fun num(key: String, group: String, label: String, unit: String, min: Double, max: Double, step: Double, def: Double?, live: Boolean = false) =
        Sig(key, group, label, unit, "number", min, max, step, def, live, null)

    private fun bool(key: String, group: String, label: String, def: Boolean?) = Sig(key, group, label, "", "bool", 0.0, 1.0, 1.0, def, false, null)

    private fun choice(key: String, group: String, label: String, def: Int, vararg options: Pair<Int, String>) =
        Sig(key, group, label, "", "enum", options.minOf { it.first }.toDouble(), options.maxOf { it.first }.toDouble(), 1.0, def.toDouble(), false, options.toList())

    private const val BATTERY = "Battery"
    private const val CHARGING = "Charging"
    private const val DRIVING = "Driving"
    private const val TRIP = "Trip"
    private const val TYRES = "Tyres"
    private const val ROAD = "RoadSense"
    private const val BODY = "Body"
    /** A cost's unit: the `currency` builtin, which the reading carries, not a fixed symbol. */
    private const val COST = "currency"

    private val SIGNALS = listOf(
        num("soc", BATTERY, "Battery level", "%", 0.0, 100.0, 1.0, 72.0),
        num("ev_range_km", BATTERY, "Range (car's figure)", "km", 0.0, 1000.0, 1.0, 318.0),
        num("calibrated_range_km", BATTERY, "Calibrated range", "km", 0.0, 1500.0, 1.0, 305.0),
        num("fuel_pct", BATTERY, "Fuel level (plug-in hybrid)", "%", 0.0, 100.0, 1.0, null),
        bool("is_phev", BATTERY, "Plug-in hybrid", false),
        bool("is_charging", CHARGING, "Charging", false),
        num("charge_power", CHARGING, "Charging power", "kW", 0.0, 350.0, 0.1, null),
        num("charge_limit", CHARGING, "Charge limit", "%", 0.0, 100.0, 1.0, 80.0),
        num("charging_eta_minutes", CHARGING, "Charging time left", "min", 0.0, 1440.0, 1.0, null),
        bool("is_dcfc", CHARGING, "DC fast charging", null),
        num("charge_added_kwh", CHARGING, "Energy added this session", "kWh", 0.0, 200.0, 0.1, null),
        num("charge_cost", CHARGING, "Cost of this charging session", COST, 0.0, 1000.0, 0.01, null),
        bool("driving", DRIVING, "Driving", false),
        bool("reversing", DRIVING, "Reversing", false),
        num("speed", DRIVING, "Speed", "km/h", 0.0, 250.0, 1.0, 0.0, live = true),
        num("power", DRIVING, "Power (negative is regen)", "kW", -150.0, 300.0, 0.1, 0.0, live = true),
        num("accel_pct", DRIVING, "Accelerator pedal", "%", 0.0, 100.0, 1.0, 0.0, live = true),
        num("brake_pct", DRIVING, "Brake pedal", "%", 0.0, 100.0, 1.0, 0.0, live = true),
        num("motor_front_rpm", DRIVING, "Front motor speed", "rpm", -16000.0, 16000.0, 10.0, 0.0, live = true),
        num("motor_rear_rpm", DRIVING, "Rear motor speed", "rpm", -16000.0, 16000.0, 10.0, 0.0, live = true),
        num("trip_km", TRIP, "Trip distance", "km", 0.0, 1000.0, 0.1, 23.4),
        num("trip_minutes", TRIP, "Trip time", "min", 0.0, 1440.0, 1.0, 31.0),
        num("trip_wh_per_km", TRIP, "Trip consumption", "Wh/km", 0.0, 400.0, 1.0, 152.0),
        num("trip_kwh", TRIP, "Trip energy", "kWh", 0.0, 150.0, 0.1, 3.6),
        num("trip_top_kmh", TRIP, "Trip top speed", "km/h", 0.0, 250.0, 1.0, 96.0),
        num("trip_cost", TRIP, "Trip cost", COST, 0.0, 1000.0, 0.01, 1.85),
        num("tyre_fl", TYRES, "Front-left tyre", "bar", 0.0, 4.0, 0.05, 2.48),
        num("tyre_fr", TYRES, "Front-right tyre", "bar", 0.0, 4.0, 0.05, 2.5),
        num("tyre_rl", TYRES, "Rear-left tyre", "bar", 0.0, 4.0, 0.05, 2.46),
        num("tyre_rr", TYRES, "Rear-right tyre", "bar", 0.0, 4.0, 0.05, 2.5),
        num("tyre_low_bar", TYRES, "Low tyre warning", "bar", 0.0, 4.0, 0.05, 2.34),
        num("tyre_high_bar", TYRES, "High tyre warning", "bar", 0.0, 4.0, 0.05, 3.1),
        bool("hazard_ahead", ROAD, "Hazard ahead", false),
        choice("hazard_type", ROAD, "Hazard type", 0, 0 to "Speed breaker", 1 to "Pothole", 2 to "Road hazard", 3 to "Rough road"),
        num("hazard_m", ROAD, "Distance to hazard", "m", 0.0, 1000.0, 5.0, 320.0),
        num("hazard_sev", ROAD, "Hazard severity", "", 1.0, 3.0, 1.0, 2.0),
        num("hazard_count", ROAD, "Hazards in a row", "", 1.0, 4.0, 1.0, 1.0),
        num("altitude_m", ROAD, "Altitude", "m", -500.0, 6000.0, 1.0, 214.0),
        num("grade_pct", ROAD, "Road grade", "%", -30.0, 30.0, 0.5, 0.0),
        bool("locked", BODY, "Locked", true),
    )

    /** Signals only the live poll carries: a widget reading one needs that poll running while it shows. */
    val LIVE_KEYS: Set<String> = SIGNALS.filter { it.live }.mapTo(LinkedHashSet()) { it.key }

    /** The builder's signal list; built on each call, so nothing is held until asked for. */
    fun catalogJson(): JSONArray {
        val out = JSONArray()
        for (s in SIGNALS) {
            val o = JSONObject()
                .put("key", s.key).put("group", s.group).put("unit", s.unit).put("type", s.type)
                .put("min", s.min).put("max", s.max).put("step", s.step).put("live", s.live)
                .put("labelKey", "widgetBuilder.sig.${s.key}").put("label", s.label)
                .put("def", s.def ?: JSONObject.NULL)
            s.options?.let { opts ->
                val a = JSONArray()
                for ((v, l) in opts) a.put(JSONArray().put(v).put(l))
                o.put("options", a)
            }
            out.put(o)
        }
        return out
    }

    /**
     * One reading as signal values. [live] is the latest live-poll sample (null when none runs);
     * [calibratedKm] is OverDrive's learned range, which the summary itself doesn't carry.
     */
    internal fun values(
        s: GlassWidgetsFeed.Snapshot?, road: GlassWidgetsFeed.Road?, live: GlassWidgetsFeed.Live?, calibratedKm: Int?,
    ): Map<String, Any?> {
        val m = HashMap<String, Any?>(64)
        val b = s?.battery
        val cs = s?.chargingState
        val trip = s?.trip
        val ty = s?.tyres
        val terrain = s?.terrain
        m["soc"] = s?.socPct?.toDouble()
        m["ev_range_km"] = b?.rangeKm?.toDouble()
        m["calibrated_range_km"] = (calibratedKm ?: b?.calibratedKm)?.toDouble()
        m["fuel_pct"] = if (s?.phev == true) b?.fuelPct?.toDouble() else null
        m["is_phev"] = s?.phev
        m["is_charging"] = s?.charging
        // core's placeholder rate, or the 0 it sends with no reading, is no figure
        m["charge_power"] = if (cs != null && !cs.kwEstimated && cs.kw > 0f) d(cs.kw) else null
        m["charge_limit"] = cs?.targetPct?.toDouble()
        m["charging_eta_minutes"] = cs?.etaMin?.toDouble()
        m["is_dcfc"] = cs?.dc
        m["driving"] = s?.driving
        m["reversing"] = s?.reversing
        m["speed"] = d(live?.kmh)
        m["power"] = d(live?.kw) ?: d(s?.packKw)
        m["accel_pct"] = d(live?.accel)
        m["brake_pct"] = d(live?.brake)
        m["motor_front_rpm"] = d(live?.frontRpm) ?: d(s?.frontRpm)
        m["motor_rear_rpm"] = d(live?.rearRpm) ?: d(s?.rearRpm)
        m["trip_km"] = d(trip?.km)
        m["trip_minutes"] = d(trip?.minutes)
        m["trip_wh_per_km"] = trip?.whPerKm?.toDouble()
        m["trip_kwh"] = d(trip?.kwh)
        m["trip_top_kmh"] = s?.tripTopKmh?.toDouble()
        m["trip_cost"] = d(trip?.cost)
        val cc = s?.chargeCost
        m["charge_added_kwh"] = d(cc?.addedKwh)
        m["charge_cost"] = d(cc?.cost)
        // the builtin both costs are shown in: the trip's currency, else the charging session's
        m["currency"] = trip?.currency ?: cc?.currency
        m["tyre_fl"] = d(ty?.getOrNull(0))
        m["tyre_fr"] = d(ty?.getOrNull(1))
        m["tyre_rl"] = d(ty?.getOrNull(2))
        m["tyre_rr"] = d(ty?.getOrNull(3))
        m["tyre_low_bar"] = d(s?.loBar)
        m["tyre_high_bar"] = d(s?.hiBar)
        m["locked"] = s?.locked
        m["altitude_m"] = terrain?.altM?.toDouble()
        m["grade_pct"] = d(terrain?.gradePct)
        // with no hazard feed the road is unknown, never "clear"; a hazard's details exist only while one is ahead
        val ahead = road?.takeIf { it.feed }?.let { !it.clear }
        m["hazard_ahead"] = ahead
        val r = road?.takeIf { ahead == true }
        m["hazard_type"] = r?.let {
            when (it.kind) {
                GlassRoadSenseView.Kind.BREAKER -> 0.0
                GlassRoadSenseView.Kind.POTHOLE -> 1.0
                GlassRoadSenseView.Kind.ROUGH -> 3.0
                GlassRoadSenseView.Kind.UNKNOWN -> 2.0
            }
        }
        m["hazard_m"] = d(r?.distanceM)
        m["hazard_sev"] = r?.severity?.toDouble()
        m["hazard_count"] = r?.cluster?.toDouble()
        return m
    }

    // a reading as a Double without float noise (2.48f, not 2.4800000190734863); NaN is unknown
    private fun d(v: Number?): Double? {
        val x = v?.toDouble() ?: return null
        return if (x.isFinite()) Math.round(x * 10000.0) / 10000.0 else null
    }
}
