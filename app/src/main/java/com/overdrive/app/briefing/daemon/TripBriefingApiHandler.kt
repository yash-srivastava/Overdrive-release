package com.overdrive.app.briefing.daemon

import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.server.HttpResponse
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.OutputStream

/**
 * The `/api/briefing` routes. Reached only through HttpServer.routeToHandlers, after AuthMiddleware.
 * Same-origin responses: the payload holds home/work coordinates.
 */
object TripBriefingApiHandler {

    private const val BASE = "/api/briefing"
    private const val MAX_BODY_CHARS = 64 * 1024
    private const val MAX_ID_CHARS = 120
    private const val MAX_SYNC_ENTRIES = 500

    private val ANSWERS = setOf(BriefingRules.YES, BriefingRules.NO, BriefingRules.LATER, BriefingRules.NONE)
    private val VIAS = setOf("voice", "card", "busy")

    private val logger = DaemonLogger.getInstance("TripBriefingApi")

    @JvmStatic
    @Throws(Exception::class)
    fun handle(method: String, path: String, body: String?, out: OutputStream): Boolean =
        handleWith({ TripBriefingService.get() }, method, path, body, out)

    internal fun handleWith(
        service: () -> TripBriefingService,
        method: String,
        path: String,
        body: String?,
        out: OutputStream,
    ): Boolean {
        val p = path.substringBefore('?').trimEnd('/')
        if (p != BASE && !p.startsWith("$BASE/")) return false
        val op = if (p == BASE) "" else p.substring(BASE.length + 1)
        val (status, reply) = try {
            route(service, method, op, body)
        } catch (e: BadRequest) {
            400 to error(e.message)
        } catch (e: BriefingUnavailable) {
            503 to error(e.message)
        } catch (t: Throwable) {
            logger.error("briefing $op failed: ${t.javaClass.simpleName}: ${t.message}")
            500 to error("internal error")
        }
        HttpResponse.sendJsonNoCors(out, status, reply.toString())
        return true
    }

    private fun route(service: () -> TripBriefingService, method: String, op: String, body: String?): Pair<Int, JSONObject> {
        val known = op in setOf("", "offer", "gear", "learn", "settings", "place", "routine", "forget", "places-sync", "answer", "go", "test-offer")
        if (!known) return 404 to error("unknown briefing route")
        val wanted = if (op == "" || op == "offer" || op == "gear") "GET" else "POST"
        if (method != wanted) return 405 to error("use $wanted")
        val svc = service()
        return when (op) {
            "" -> 200 to svc.payload()
            "offer" -> svc.pendingOfferJson()?.let { 200 to ok().put("tripBriefing", it) }
                ?: (200 to JSONObject().put("ok", false))
            "learn" -> when (svc.learn()) {
                TripBriefingService.LearnResult.DONE -> 200 to svc.payload()
                // Still running: the result lands in a later GET (learnedMs moves on).
                TripBriefingService.LearnResult.PENDING -> 202 to ok().put("learning", true)
                TripBriefingService.LearnResult.UNAVAILABLE -> 503 to error("trip history is not available yet")
            }
            "settings" -> {
                val patch = TripBriefingSettings.patchFrom(json(body))
                if (!svc.updateSettings(patch)) 503 to error("settings could not be saved") else 200 to svc.payload()
            }
            "place" -> {
                val req = json(body)
                val at = BriefingJson.point(req)
                svc.setPlace(at, if (req.opt("remove") == true) null else BriefingJson.name(req.opt("name")))
                200 to svc.payload()
            }
            "routine" -> routine(svc, json(body))
            "forget" -> {
                svc.forget()
                200 to svc.payload()
            }
            "places-sync" -> {
                val req = json(body)
                svc.placesSync(savedList(req), recentList(req))
                200 to ok()
            }
            "answer" -> answer(svc, json(body))
            // Blocks (up to GO_MAX_WAIT_MS) until the live gear isn't R: the app opens directions on the reply.
            "go" -> 200 to ok().put("go", svc.awaitLaunch(id(json(body).opt("id"))))
            // The card's quick gear check while it's on screen (a status poll is up to a second apart).
            "gear" -> 200 to ok().put("gear", svc.gearFresh() ?: JSONObject.NULL)
            "test-offer" -> 200 to ok().put("tripBriefing", svc.testOffer())
            else -> 404 to error("unknown briefing route")
        }
    }

    private fun routine(svc: TripBriefingService, req: JSONObject): Pair<Int, JSONObject> {
        val id = id(req.opt("id"))
        val hidden = if (req.has("hidden")) req.opt("hidden") as? Boolean ?: throw BadRequest("hidden must be true or false") else null
        val appGiven = req.has("app")
        val app = req.opt("app").let { if (it == null || it == JSONObject.NULL || it == "") null else BriefingJson.app(it) }
        // {paused: false} lifts a pause the driver's "no"s put on the routine.
        val resume = req.opt("paused") == false
        if (hidden == null && !appGiven && !resume) throw BadRequest("nothing to change")
        if (!svc.setRoutine(id, hidden, appGiven, app, resume)) return 404 to error("unknown routine")
        return 200 to svc.payload()
    }

    private fun answer(svc: TripBriefingService, req: JSONObject): Pair<Int, JSONObject> {
        val id = id(req.opt("id"))
        val answer = (req.opt("answer") as? String)?.takeIf { it in ANSWERS }
            ?: throw BadRequest("answer must be yes, no, later or none")
        val via = (req.opt("via") as? String)?.takeIf { it in VIAS }
            ?: throw BadRequest("via must be voice, card or busy")
        val app = req.opt("app").let { if (it == null || it == JSONObject.NULL) null else BriefingJson.app(it) }
        return if (svc.answer(id, answer, app, via)) 200 to ok() else 200 to JSONObject().put("ok", false).put("error", "unknown offer")
    }

    /** At most 50 kept, Home and Work first. */
    private fun savedList(req: JSONObject): List<SavedPlace> = places(req, "saved") { e ->
        val kind = (e.opt("kind") as? String)?.takeIf { it in BriefingState.SAVED_KINDS }
            ?: throw BadRequest("saved kind must be home, work or fav")
        val at = coordinates(e) ?: return@places null
        SavedPlace(kind, BriefingJson.cleanLabel(e.opt("label")) ?: "", at.lat, at.lng)
    }.sortedBy { if (it.kind == "fav") 1 else 0 }.take(BriefingState.MAX_PLACES)

    private fun recentList(req: JSONObject): List<RecentPlace> = places(req, "recent") { e ->
        val at = coordinates(e) ?: return@places null
        BriefingJson.cleanLabel(e.opt("label"))?.let { RecentPlace(it, at.lat, at.lng) }
    }.take(BriefingState.MAX_PLACES)

    /** An absent key is an empty list; entries the parser drops (no label, no fix) are skipped. */
    private inline fun <T> places(req: JSONObject, key: String, parse: (JSONObject) -> T?): List<T> {
        if (!req.has(key) || req.isNull(key)) return emptyList()
        val arr = req.opt(key) as? JSONArray ?: throw BadRequest("$key must be a list")
        if (arr.length() > MAX_SYNC_ENTRIES) throw BadRequest("$key holds more than $MAX_SYNC_ENTRIES places")
        val out = ArrayList<T>()
        for (i in 0 until arr.length()) {
            val e = arr.opt(i) as? JSONObject ?: throw BadRequest("$key entries must be objects")
            parse(e)?.let { out.add(it) }
        }
        return out
    }

    /** Validated coordinates; null for the (0,0) "no fix" placeholder, which places can legitimately carry. */
    private fun coordinates(e: JSONObject): LatLng? {
        val at = LatLng(BriefingJson.lat(e), BriefingJson.lng(e))
        return if (BriefingGeo.usable(at.lat, at.lng)) at else null
    }

    private fun id(raw: Any?): String {
        val s = (raw as? String)?.trim()
        if (s.isNullOrEmpty() || s.length > MAX_ID_CHARS) throw BadRequest("id must be a non-empty string")
        return s
    }

    private fun json(body: String?): JSONObject {
        if (body.isNullOrBlank()) throw BadRequest("JSON body required")
        if (body.length > MAX_BODY_CHARS) throw BadRequest("body too large")
        return try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw BadRequest("body must be a JSON object")
        } catch (e: StackOverflowError) {
            throw BadRequest("body must be a JSON object")
        }
    }

    private fun ok(): JSONObject = JSONObject().put("ok", true)

    private fun error(message: String?): JSONObject = JSONObject().put("ok", false).put("error", message ?: "error")
}
