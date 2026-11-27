package com.overdrive.app.briefing.daemon

import com.overdrive.app.briefing.engine.BriefPlace
import com.overdrive.app.briefing.engine.BriefingModel
import com.overdrive.app.briefing.engine.Routine
import com.overdrive.app.briefing.engine.RoutineKey
import com.overdrive.app.briefing.engine.RoutineScore
import com.overdrive.app.briefing.engine.SelfTest
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class BadRequest(message: String) : IllegalArgumentException(message)

internal class BriefingUnavailable(message: String) : Exception(message)

/** Wire shapes of /api/briefing and the /status field, and request validation. */
internal object BriefingJson {

    const val MAX_NAME = 40

    fun r6(v: Double): Double = Math.round(v * 1e6) / 1e6

    fun r1(v: Double): Double = if (v.isFinite()) Math.round(v * 10.0) / 10.0 else 0.0

    fun hhmm(minuteOfDay: Int): String {
        val m = Math.floorMod(minuteOfDay, 1440)
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    fun place(p: BriefPlace): JSONObject = JSONObject()
        .put("label", p.label ?: JSONObject.NULL)
        .put("role", p.role?.name ?: JSONObject.NULL)
        .put("lat", r6(p.lat))
        .put("lng", r6(p.lng))

    fun routine(r: Routine, score: RoutineScore?, pausedUntil: Long? = null): JSONObject = JSONObject()
        .put("id", r.id)
        .put("from", place(r.from))
        .put("to", place(r.to))
        .put("dayType", r.dayType.name)
        .put("time", hhmm(r.typicalStartMin))
        .put("trips", r.trips)
        .put("lastMs", r.lastMs)
        .put("durationS", r.medianDurationS)
        .put("distanceKm", r1(r.medianDistanceKm))
        .put("hidden", r.hidden)
        .put("pausedUntil", pausedUntil ?: JSONObject.NULL)
        .put("app", r.app ?: JSONObject.NULL)
        .put("score", score?.let { JSONObject().put("offers", it.offers).put("correct", it.correct) } ?: JSONObject.NULL)

    fun selfTest(st: SelfTest): JSONObject {
        val routines = JSONArray()
        for (s in st.routines) {
            routines.put(JSONObject().put("id", s.routineId).put("offers", s.offers).put("correct", s.correct))
        }
        return JSONObject()
            .put("trips", st.trips)
            .put("offers", st.offers)
            .put("correct", st.correct)
            .put("firstOfferDay", st.firstOfferDay ?: JSONObject.NULL)
            .put("routines", routines)
    }

    /** The routine's self-test score: same id, else the same ends within [radiusM] (centroids drift). */
    fun scoreFor(r: Routine, st: SelfTest?, radiusM: Double): RoutineScore? {
        val scores = st?.routines ?: return null
        scores.firstOrNull { it.routineId == r.id }?.let { return it }
        val key = RoutineKey(r.from.lat, r.from.lng, r.to.lat, r.to.lng, r.dayType)
        return scores.firstOrNull { s ->
            BriefingGeo.parseRoutineId(s.routineId)?.let { BriefingGeo.sameRoutine(it, key, radiusM) } == true
        }
    }

    /** GET /api/briefing. [tripCount] is how many trips the model learned from, null before the first load. */
    fun payload(
        settings: TripBriefingSettings,
        model: BriefingModel?,
        state: BriefingState,
        radiusM: Double,
        learning: Boolean,
        tripCount: Int?,
        nowMs: Long,
        cfg: com.overdrive.app.briefing.engine.BriefingConfig,
    ): JSONObject {
        val routines = JSONArray()
        val unnamed = JSONArray()
        if (model != null) {
            for (r in model.routines) {
                val key = RoutineKey(r.from.lat, r.from.lng, r.to.lat, r.to.lng, r.dayType)
                routines.put(routine(r, scoreFor(r, state.selfTest, radiusM), BriefingRules.pausedUntil(state, key, nowMs, cfg)))
            }
            for (p in model.unnamed()) {
                unnamed.put(JSONObject().put("lat", r6(p.lat)).put("lng", r6(p.lng)).put("trips", p.visits))
            }
        }
        val out = JSONObject()
            .put("ok", true)
            .put("settings", settings.toJson())
            .put("routines", routines)
            .put("unnamed", unnamed)
            .put("selfTest", state.selfTest?.let { selfTest(it) } ?: JSONObject.NULL)
            .put("learnedMs", state.learnedMs)
            .put("ready", model != null)
            .put("learning", learning)
        if (tripCount != null) out.put("trips", tripCount)
        return out
    }

    /** GET /api/briefing/offer's `tripBriefing`: the whole offer (/status carries only its id and expiry). */
    fun offer(p: PublishedOffer): JSONObject = JSONObject()
        .put("id", p.id)
        .put("expiresAt", p.expiresAt)
        .put("dest", JSONObject()
            .put("label", p.dest.label ?: JSONObject.NULL)
            .put("role", p.dest.role?.name ?: JSONObject.NULL)
            .put("lat", r6(p.dest.lat))
            .put("lng", r6(p.dest.lng)))
        .put("durationS", p.durationS)
        .put("distanceKm", r1(p.distanceKm))
        .put("extra", p.extra?.toJson() ?: JSONObject.NULL)
        .put("voice", p.voice)
        .put("app", p.app)

    fun lat(o: JSONObject, key: String = "lat"): Double = number(o, key, 90.0)

    fun lng(o: JSONObject, key: String = "lng"): Double = number(o, key, 180.0)

    /** Finite, in range, and not the (0,0) "no fix" placeholder. */
    fun point(o: JSONObject): LatLng {
        val p = LatLng(lat(o), lng(o))
        if (!BriefingGeo.usable(p.lat, p.lng)) throw BadRequest("invalid coordinates")
        return p
    }

    /** A user-given place name: trimmed, 1-40 characters, no control characters. */
    fun name(raw: Any?): String {
        val s = (raw as? String)?.trim() ?: throw BadRequest("name must be a string")
        val n = s.codePointCount(0, s.length)
        if (n < 1 || n > MAX_NAME) throw BadRequest("name must be 1-$MAX_NAME characters")
        if (s.any { isControl(it) }) throw BadRequest("name must not contain control characters")
        if (!shows(s) || withoutLoneSurrogates(s) != s) throw BadRequest("name must have visible characters")
        return s
    }

    /** A synced label: control characters dropped, trimmed, cut to 40 characters; null when nothing is left. */
    fun cleanLabel(raw: Any?): String? {
        val s = raw as? String ?: return null
        val cleaned = buildString { for (c in withoutLoneSurrogates(s)) if (!isControl(c)) append(c) }.trim()
        val limited = if (cleaned.codePointCount(0, cleaned.length) <= MAX_NAME) cleaned
            else cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_NAME)).trim()
        // Truncation can remove the only visible characters in a synced label.
        return limited.takeIf(::shows)
    }

    fun app(raw: Any?): String {
        val s = raw as? String
        if (s == null || s !in TripBriefingSettings.APPS) {
            throw BadRequest("app must be one of " + TripBriefingSettings.APPS.joinToString(", "))
        }
        return s
    }

    private fun number(o: JSONObject, key: String, limit: Double): Double {
        val v = (o.opt(key) as? Number)?.toDouble() ?: throw BadRequest("$key must be a number")
        if (!v.isFinite() || v < -limit || v > limit) throw BadRequest("$key out of range")
        return v
    }

    private fun isControl(c: Char): Boolean = Character.isISOControl(c) || c == '\u2028' || c == '\u2029'

    // something shows: not only spaces, invisible format characters (U+200B…), lone marks or fillers
    private fun shows(s: String): Boolean = s.any { c ->
        val type = Character.getType(c).toByte()
        !c.isWhitespace() && type != Character.FORMAT && type != Character.NON_SPACING_MARK &&
            type != Character.ENCLOSING_MARK && c !in FILLERS
    }

    private const val FILLERS = "\u115f\u1160\u3164\uffa0"

    // a lone surrogate can't be written as UTF-8
    private fun withoutLoneSurrogates(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                out.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (!Character.isSurrogate(c)) out.append(c)
            i++
        }
        return out.toString()
    }
}
