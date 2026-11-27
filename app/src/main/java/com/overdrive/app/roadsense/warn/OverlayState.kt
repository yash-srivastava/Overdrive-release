package com.overdrive.app.roadsense.warn

import org.json.JSONObject

/**
 * The cross-process overlay state (D-024): the daemon's WarningCoordinator publishes
 * this to [RoadSenseOverlayChannel]; the app-side overlays read it over the loopback
 * IPC and render it. Both sides share THIS codec so the JSON keys never drift.
 * [SECTION]/[KEY] name the legacy config key, still read as a fallback for one release.
 *
 * Pure data + JSON (no Android), so both sides + tests use it identically.
 */
data class OverlayState(
    /** Per-VEHICLE calibration maturity → the green/orange/red dot (R-OVL-1).
     *  0..1; <0.34 red (learning the car), <0.67 orange, else green. This is about
     *  how well we've learned THIS car's suspension (severity trust), NOT about
     *  whether the current road is mapped — see [coverage] for that. */
    val calibrationLevel: Float,
    /** Route COVERAGE of the tile we're currently in (distinct from [calibrationLevel]):
     *  0 = new road (no data — "no hazard" only means "unknown"), 1 = seen once,
     *  2 = mapped (driven enough to trust a clear reading). Drives the idle caption
     *  ("New road" vs "Road mapped · clear") so a clear overlay on an unsurveyed road
     *  isn't mistaken for a confirmed-safe road. */
    val coverage: Int = 0,
    /** Is a hazard currently ahead within interest range? When false the card
     *  shows the idle/"scanning" state. */
    val hazardAhead: Boolean,
    /** Metres to the next hazard ahead (only meaningful when [hazardAhead]). */
    val nextHazardMeters: Int,
    /** Signed bearing to the hazard relative to travel, −180..+180 (0 = ahead).
     *  Drives the direction arrow (R-OVL-2). */
    val nextHazardRelBearingDeg: Int,
    /** Severity of the next hazard: 1=minor, 2=moderate, 3=severe (0 = none). */
    val nextHazardSeverity: Int,
    /** 0=breaker, 1=pothole, 2=unknown, 3=rough_section — for the icon. */
    val nextHazardType: Int,
    /** Number of hazards in the zone ahead (D-032). 1 = a single hazard; >1 = a
     *  cluster the overlay renders as "N bumps ahead". 0 when nothing ahead. */
    val zoneCount: Int,
    /** Along-track length of the zone ahead in metres (D-032). >0 for a multi-
     *  hazard cluster / rough section → "rough section, N m". 0 for a singleton. */
    val zoneLengthM: Int,
    /** True when the zone ahead is a sustained rough/washboard stretch (D-032) —
     *  the overlay announces it as a section, not a count. */
    val zoneRough: Boolean,
    /** A pending Calibration-Mode confirm card to show (R-OVL-6), or null. */
    val pendingConfirm: PendingConfirm?,
    /** Wall-clock ms the daemon wrote this — the app treats very stale state as
     *  "no data" (daemon down / not driving). */
    val updatedMs: Long,
) {
    /** Calibration traffic-light bucket for the dot. */
    enum class CalLevel { RED, ORANGE, GREEN }

    fun calLevel(): CalLevel = when {
        calibrationLevel < 0.34f -> CalLevel.RED
        calibrationLevel < 0.67f -> CalLevel.ORANGE
        else -> CalLevel.GREEN
    }

    /** The algorithm's pre-filled assessment the user confirms/corrects (R-OVL-6). */
    data class PendingConfirm(
        val hazardId: String,
        val algoType: Int,      // 0=breaker,1=pothole,2=unknown
        val algoSeverity: Int,  // 1..3
        val algoConfidence: Float,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put(K_CAL, calibrationLevel.toDouble())
        put(K_COVERAGE, coverage)
        put(K_AHEAD, hazardAhead)
        put(K_METERS, nextHazardMeters)
        put(K_BEARING, nextHazardRelBearingDeg)
        put(K_SEVERITY, nextHazardSeverity)
        put(K_TYPE, nextHazardType)
        put(K_ZONE_COUNT, zoneCount)
        put(K_ZONE_LEN, zoneLengthM)
        put(K_ZONE_ROUGH, zoneRough)
        put(K_UPDATED, updatedMs)
        pendingConfirm?.let {
            put(K_PENDING, JSONObject().apply {
                put(PK_ID, it.hazardId)
                put(PK_TYPE, it.algoType)
                put(PK_SEV, it.algoSeverity)
                put(PK_CONF, it.algoConfidence.toDouble())
            })
        }
    }

    companion object {
        /** Legacy config section + key (fallback only; the daemon no longer writes it). */
        const val SECTION = "roadSense"
        const val KEY = "overlayState"

        private const val K_CAL = "cal"
        private const val K_COVERAGE = "cov"
        private const val K_AHEAD = "ahead"
        private const val K_METERS = "m"
        private const val K_BEARING = "brg"
        private const val K_SEVERITY = "sev"
        private const val K_TYPE = "type"
        private const val K_ZONE_COUNT = "zc"
        private const val K_ZONE_LEN = "zl"
        private const val K_ZONE_ROUGH = "zr"
        private const val K_PENDING = "pending"
        private const val K_UPDATED = "ts"
        private const val PK_ID = "id"
        private const val PK_TYPE = "type"
        private const val PK_SEV = "sev"
        private const val PK_CONF = "conf"

        /** Idle state when nothing is ahead (calibration + coverage still shown). */
        fun idle(calibrationLevel: Float, coverage: Int, updatedMs: Long) = OverlayState(
            calibrationLevel = calibrationLevel,
            coverage = coverage,
            hazardAhead = false, nextHazardMeters = 0, nextHazardRelBearingDeg = 0,
            nextHazardSeverity = 0, nextHazardType = 2,
            zoneCount = 0, zoneLengthM = 0, zoneRough = false,
            pendingConfirm = null,
            updatedMs = updatedMs,
        )

        fun fromJson(o: JSONObject?): OverlayState? {
            if (o == null) return null
            val pending = o.optJSONObject(K_PENDING)?.let {
                PendingConfirm(
                    hazardId = it.optString(PK_ID),
                    algoType = it.optInt(PK_TYPE, 2),
                    algoSeverity = it.optInt(PK_SEV, 1),
                    algoConfidence = it.optDouble(PK_CONF, 0.0).toFloat(),
                )
            }
            return OverlayState(
                calibrationLevel = o.optDouble(K_CAL, 0.0).toFloat(),
                coverage = o.optInt(K_COVERAGE, 0),
                hazardAhead = o.optBoolean(K_AHEAD, false),
                nextHazardMeters = o.optInt(K_METERS, 0),
                nextHazardRelBearingDeg = o.optInt(K_BEARING, 0),
                nextHazardSeverity = o.optInt(K_SEVERITY, 0),
                nextHazardType = o.optInt(K_TYPE, 2),
                zoneCount = o.optInt(K_ZONE_COUNT, 0),
                zoneLengthM = o.optInt(K_ZONE_LEN, 0),
                zoneRough = o.optBoolean(K_ZONE_ROUGH, false),
                pendingConfirm = pending,
                updatedMs = o.optLong(K_UPDATED, 0L),
            )
        }
    }
}
