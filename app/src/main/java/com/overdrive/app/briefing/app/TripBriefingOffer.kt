package com.overdrive.app.briefing.app

import android.content.Context
import android.text.format.DateFormat
import com.overdrive.app.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** The pending offer from the /status "tripBriefing" field (dev/trip-briefing/00-DESIGN.md §3). */
internal class TripBriefingOffer(
    val id: String,
    val expiresAt: Long,
    val label: String?,
    val role: String?,
    val lat: Double,
    val lng: Double,
    val durationS: Int,
    val extra: Extra?,
    val voice: Boolean,
    val app: String?,
) {
    sealed class Extra {
        class Range(val rangeKm: Double, val tripKm: Double) : Extra()
        class Hazards(val count: Int) : Extra()
        class Rain(val hour: Int) : Extra()
    }

    companion object {
        /** Null when the field is malformed. A missing expiresAt reads as 20 s from now. */
        fun parse(o: JSONObject, nowMs: Long): TripBriefingOffer? {
            val id = text(o, "id") ?: return null
            val dest = o.optJSONObject("dest") ?: return null
            val lat = dest.optDouble("lat", Double.NaN)
            val lng = dest.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            val expiresAt = o.optLong("expiresAt", 0L).takeIf { it > 0L } ?: (nowMs + 20_000L)
            return TripBriefingOffer(
                id = id,
                expiresAt = expiresAt,
                label = text(dest, "label"),
                role = text(dest, "role")?.uppercase(Locale.ROOT),
                lat = lat,
                lng = lng,
                durationS = max(0, o.optInt("durationS", 0)),
                extra = o.optJSONObject("extra")?.let(::extra),
                voice = o.optBoolean("voice", false),
                app = text(o, "app"),
            )
        }

        private fun extra(e: JSONObject): Extra? = when (e.optString("type")) {
            "range" -> {
                val range = e.optDouble("rangeKm", Double.NaN)
                val trip = e.optDouble("tripKm", Double.NaN)
                if (range >= 0.0 && trip > 0.0) Extra.Range(range, trip) else null
            }
            "hazards" -> e.optInt("count", 0).takeIf { it > 0 }?.let { Extra.Hazards(it) }
            "rain" -> e.optInt("hour", -1).takeIf { it in 0..23 }?.let { Extra.Rain(it) }
            else -> null
        }

        // org.json's optString turns a JSON null into "null"
        private fun text(o: JSONObject, key: String): String? =
            if (o.isNull(key)) null else o.optString(key, "").trim().takeIf { it.isNotEmpty() }
    }
}

/** Everything the card and the voice turn say about one offer, in the app language. */
internal class TripBriefingTexts(
    /** Card title: Home, Work, the label, or "Your usual stop". */
    val title: String,
    /** Duration, or the extra that replaces it; null when neither is known. */
    val subtitle: String?,
    val subtitleIsExtra: Boolean,
    /** The place has a label or a role; an unnamed place gets no voice and a "Name this place" key. */
    val named: Boolean,
    /** Null for an unnamed place. */
    val spokenLine: String?,
    /** The destination as said ("work", "Phoenix Mall"), for the AI. */
    val destSaid: String,
    val durationMin: Int,
    val extraLine: String?,
    val question: String,
) {
    companion object {
        private const val KM_PER_MI = 1.609344

        /** [ctx] resolves the app language. */
        fun build(ctx: Context, o: TripBriefingOffer, miles: Boolean, nowMs: Long): TripBriefingTexts {
            val res = ctx.resources
            val home = o.role == "HOME"
            val work = o.role == "WORK"
            val label = o.label
            val named = home || work || label != null
            val minutes = if (o.durationS > 0) max(1, (o.durationS / 60.0).roundToInt()) else 0
            val duration = if (minutes > 0) res.getQuantityString(R.plurals.trip_briefing_duration, minutes, minutes) else null
            val extraLine = o.extra?.let { phrase(ctx, it, miles, nowMs) }
            val body = extraLine ?: duration
            val question = ctx.getString(R.string.trip_briefing_question)
            val spoken = if (!named) null else {
                val heading = when {
                    home -> ctx.getString(R.string.trip_briefing_heading_home)
                    work -> ctx.getString(R.string.trip_briefing_heading_work)
                    else -> ctx.getString(R.string.trip_briefing_heading_place, label)
                }
                listOfNotNull(greeting(ctx, nowMs), heading, body, question).joinToString(" ")
            }
            return TripBriefingTexts(
                title = when {
                    home -> ctx.getString(R.string.trip_briefing_title_home)
                    work -> ctx.getString(R.string.trip_briefing_title_work)
                    label != null -> label
                    else -> ctx.getString(R.string.trip_briefing_title_unnamed)
                },
                subtitle = body,
                subtitleIsExtra = extraLine != null,
                named = named,
                spokenLine = spoken,
                destSaid = when {
                    home -> ctx.getString(R.string.trip_briefing_said_home)
                    work -> ctx.getString(R.string.trip_briefing_said_work)
                    else -> label ?: ctx.getString(R.string.trip_briefing_title_unnamed)
                },
                durationMin = minutes,
                extraLine = extraLine,
                question = question,
            )
        }

        private fun greeting(ctx: Context, nowMs: Long): String {
            val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
            return ctx.getString(when (hour) {
                in 4..11 -> R.string.trip_briefing_greeting_morning
                in 12..17 -> R.string.trip_briefing_greeting_afternoon
                else -> R.string.trip_briefing_greeting_evening
            })
        }

        private fun phrase(ctx: Context, e: TripBriefingOffer.Extra, miles: Boolean, nowMs: Long): String? {
            val res = ctx.resources
            return when (e) {
                is TripBriefingOffer.Extra.Range ->
                    ctx.getString(R.string.trip_briefing_extra_range, distance(ctx, e.rangeKm, miles), distance(ctx, e.tripKm, miles))
                is TripBriefingOffer.Extra.Hazards ->
                    res.getQuantityString(R.plurals.trip_briefing_extra_hazards, e.count, e.count)
                is TripBriefingOffer.Extra.Rain -> ctx.getString(R.string.trip_briefing_extra_rain, hourText(ctx, e.hour, nowMs))
            }
        }

        private fun distance(ctx: Context, km: Double, miles: Boolean): String {
            val v = max(0, (if (miles) km / KM_PER_MI else km).roundToInt())
            return ctx.getString(if (miles) R.string.trip_briefing_distance_mi else R.string.trip_briefing_distance_km, v)
        }

        /** "7 PM" or "19:00", following the device's 12/24-hour setting. */
        private fun hourText(ctx: Context, hour: Int, nowMs: Long): String {
            val locale = ctx.resources.configuration.locales.get(0) ?: Locale.getDefault()
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }
            val is24 = DateFormat.is24HourFormat(ctx)
            return try {
                SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "ha"), locale).format(cal.time)
            } catch (_: RuntimeException) {
                SimpleDateFormat(if (is24) "HH:mm" else "h a", locale).format(cal.time)
            }
        }
    }
}
