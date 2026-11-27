package com.overdrive.app.navmap.ui

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/** Every user-visible HUD string, resolved once from resources by [HudRes]. */
class HudStrings(
    /** Verb without a street ("Turn right") and as a lead-in to one ("Turn right onto"), by [HudVerb.ordinal]. */
    val bare: Array<String>,
    val onto: Array<String>,
    val ordinals: Array<String>,
    val roundaboutExitBare: String,
    val roundaboutExitOnto: String,
    val towards: String,
    val exit: String,
    val then: String,
    val arrive: String,
    val recenter: String,
    val end: String,
    val delay: String,
    val unitM: String,
    val unitKm: String,
    val unitFt: String,
    val unitMi: String,
    val unitMin: String,
    val unitH: String,
    val unitKmh: String,
    val unitMph: String,
    val attribution: String,
    val cdMute: String,
    val cdUnmute: String,
    val cdOverview: String,
    val cdOverviewExit: String,
    val cdEnd: String,
    val cdRecenter: String,
    val cdCompassHeadingUp: String,
    val cdCompassNorthUp: String,
    val cdBack: String,
    val cdThen: String,
    val cdTrip: String,
    val cdDelay: String,
)

enum class HudVerb {
    CONTINUE, DEPART, ARRIVE, ARRIVE_RIGHT, ARRIVE_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, SHARP_LEFT, LEFT, SLIGHT_LEFT,
    RAMP, EXIT, KEEP_STRAIGHT, KEEP_RIGHT, KEEP_LEFT, MERGE, MERGE_RIGHT, MERGE_LEFT,
    ROUNDABOUT, ROUNDABOUT_LEAVE, FERRY, FERRY_LEAVE;

    companion object {
        /** Valhalla maneuver type → verb; unknown and pedestrian/transit types read as Continue. */
        fun of(type: Int): HudVerb = when (type) {
            1, 2, 3 -> DEPART
            4 -> ARRIVE
            5 -> ARRIVE_RIGHT
            6 -> ARRIVE_LEFT
            9 -> SLIGHT_RIGHT
            10 -> RIGHT
            11 -> SHARP_RIGHT
            12, 13 -> UTURN
            14 -> SHARP_LEFT
            15 -> LEFT
            16 -> SLIGHT_LEFT
            17, 18, 19 -> RAMP
            20, 21 -> EXIT
            22 -> KEEP_STRAIGHT
            23 -> KEEP_RIGHT
            24 -> KEEP_LEFT
            25 -> MERGE
            26 -> ROUNDABOUT
            27 -> ROUNDABOUT_LEAVE
            28 -> FERRY
            29 -> FERRY_LEAVE
            37 -> MERGE_RIGHT
            38 -> MERGE_LEFT
            else -> CONTINUE
        }
    }
}

/** Verb, main line and sign line for a maneuver, from [HudStrings]. */
class HudLabels(val s: HudStrings) {

    /** The road the maneuver leads onto, else the instruction; null when it would only repeat the verb. */
    fun mainLine(m: HudManeuver): String? {
        m.streetNames.firstOrNull { it.isNotBlank() }?.let { return it.trim() }
        val ins = m.instruction.trim()
        if (ins.isEmpty() || ins.trimEnd('.').equals(verb(m, false), ignoreCase = true)) return null
        return ins
    }

    fun verb(m: HudManeuver): String = verb(m, m.streetNames.any { it.isNotBlank() })

    fun verb(m: HudManeuver, onto: Boolean): String {
        val v = HudVerb.of(m.type)
        if (v == HudVerb.ROUNDABOUT && m.roundaboutExit > 0) {
            val n = m.roundaboutExit
            val ord = if (n <= s.ordinals.size) s.ordinals[n - 1] else n.toString()
            return String.format(if (onto) s.roundaboutExitOnto else s.roundaboutExitBare, ord)
        }
        return if (onto) s.onto[v.ordinal] else s.bare[v.ordinal]
    }

    /** Up to two signed destinations joined " / "; empty when the maneuver has none. */
    fun towardsNames(m: HudManeuver): String =
        m.towards.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" / ")

    fun exitLabel(m: HudManeuver): String =
        if (m.exitNumber.isBlank()) "" else String.format(s.exit, m.exitNumber.trim())

    fun distanceUnit(code: Int): String = when (code) {
        HudFormat.KM -> s.unitKm
        HudFormat.FT -> s.unitFt
        HudFormat.MI -> s.unitMi
        else -> s.unitM
    }

    fun duration(minutes: Long): String = when {
        minutes < 60 -> "$minutes ${s.unitMin}"
        minutes % 60 == 0L -> "${minutes / 60} ${s.unitH}"
        else -> "${minutes / 60} ${s.unitH} ${minutes % 60} ${s.unitMin}"
    }

    fun distance(key: Long): String = HudFormat.number(key) + " " + distanceUnit(HudFormat.unit(key))
}

/** Rounding for the HUD's distances and times. A key changes only when the text it formats to changes. */
object HudFormat {
    const val M = 0
    const val KM = 1
    const val FT = 2
    const val MI = 3

    private const val M_PER_FT = 0.3048
    private const val M_PER_TENTH_MI = 160.9344

    /**
     * Metric: 5 m steps under 100 m, 10 m under 500 m, 50 m under 1 km, then 0.1 km, whole km from 10 km.
     * Imperial: 50 ft steps under 0.1 mi, then 0.1 mi.
     */
    fun distanceKey(meters: Double, imperial: Boolean): Long {
        val m = if (meters.isNaN() || meters < 0.0) 0.0 else meters
        if (imperial) {
            val ft = m / M_PER_FT
            if (ft < 525.0) return key(FT, (ft / 50.0).roundToLong() * 50L)
            return key(MI, max(1L, (m / M_PER_TENTH_MI).roundToLong()))
        }
        if (m < 100.0) return key(M, (m / 5.0).roundToLong() * 5L)
        if (m < 500.0) return key(M, (m / 10.0).roundToLong() * 10L)
        if (m < 1000.0) {
            val v = (m / 50.0).roundToLong() * 50L
            return if (v >= 1000L) key(KM, 10L) else key(M, v)
        }
        val tenths = (m / 100.0).roundToLong()
        if (tenths < 100L) return key(KM, tenths)
        return key(KM, (m / 1000.0).roundToLong() * 10L)
    }

    fun unit(key: Long): Int = (key ushr 40).toInt()

    /** "180", "6.6", "12" in the default locale's digits and decimal mark. */
    fun number(key: Long): String {
        val v = key and 0xFFFFFFFFFFL
        val locale = Locale.getDefault()
        return when (unit(key)) {
            KM -> if (v >= 100L) String.format(locale, "%d", v / 10L) else String.format(locale, "%.1f", v / 10.0)
            MI -> String.format(locale, "%.1f", v / 10.0)
            else -> String.format(locale, "%d", v)
        }
    }

    /** Whole minutes, never 0 while any time remains. */
    fun minutes(seconds: Double): Long {
        if (seconds.isNaN() || seconds <= 0.0) return 0L
        return max(1L, (seconds / 60.0).roundToLong())
    }

    private fun key(unit: Int, value: Long): Long = (unit.toLong() shl 40) or (value and 0xFFFFFFFFFFL)
}
