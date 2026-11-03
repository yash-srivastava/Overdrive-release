package com.overdrive.app.server

import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.daemon.CameraDaemon
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.overlay.GlassWidgetsFeed
import com.overdrive.app.overlay.glass.CustomDoc
import com.overdrive.app.overlay.glass.CustomSignals
import com.overdrive.app.trips.TripApiHandler
import com.overdrive.app.widgets.CustomWidgetStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Widget builder routes: the signal catalog, the car's current readings and its custom widget store.
 * HttpServer requires a JWT for every non-GET request routed here.
 */
object CustomWidgetsApiHandler {

    private const val SIGNALS = "/api/widgets/signals"
    private const val VALUES = "/api/widgets/values"
    private const val CUSTOM = "/api/widgets/custom"
    // a pretty-printed 16 KB doc plus its envelope; anything bigger is refused before parsing
    private const val MAX_BODY_CHARS = 4 * CustomWidgetStore.MAX_BYTES

    private val logger = DaemonLogger.getInstance("CustomWidgetsApi")

    @JvmStatic
    @Throws(Exception::class)
    fun handle(method: String, path: String, body: String?, out: OutputStream): Boolean {
        val p = path.substringBefore('?')
        try {
            when {
                p == SIGNALS -> if (method == "GET") signals(out) else notAllowed(out)
                p == VALUES -> if (method == "GET") values(out) else notAllowed(out)
                p == CUSTOM -> if (method == "GET") list(out) else notAllowed(out)
                p.startsWith("$CUSTOM/") -> item(method, p.substring(CUSTOM.length + 1).split('/'), body, out)
                else -> return false
            }
        } catch (t: Throwable) {
            logger.error("custom widgets: ${t.message}")
            fail(out, 500, Messages.get("errors.widgets_internal_error"))
        }
        return true
    }

    private fun item(method: String, parts: List<String>, body: String?, out: OutputStream) {
        val id = parts[0]
        val show = parts.size == 2 && parts[1] == "show"
        when {
            parts.size == 1 && method == "PUT" -> put(id, body, out)
            parts.size == 1 && method == "DELETE" -> delete(id, out)
            show && method == "POST" -> show(id, body, out)
            parts.size == 1 || show -> notAllowed(out)
            else -> fail(out, 404, Messages.get("errors.widgets_unknown_route"))
        }
    }

    private fun signals(out: OutputStream) {
        HttpResponse.sendJson(out, JSONObject().put("success", true)
            .put("signals", CustomSignals.catalogJson()).put("units", units()).toString())
    }

    /** The car's display units, read the way the overlay reads them; defaults when the config can't be read. */
    private fun units(): JSONObject {
        var miles = false
        var tyre = UnifiedConfigManager.TYRE_PRESSURE_UNIT_DEFAULT
        try {
            miles = UnifiedConfigManager.loadConfig().optJSONObject("tripAnalytics")?.optString("distanceUnit") == "mi"
            tyre = UnifiedConfigManager.getTyrePressureUnit()
        } catch (e: Exception) {
            logger.warn("units unavailable: ${e.message}")
        }
        return JSONObject().put("distance", if (miles) "mi" else "km").put("tyre", tyre)
    }

    /**
     * What the car overlay would hand a custom widget now, from the reads it polls, made in this process:
     * the launcher summary, the live sample (the overlay polls it only while driving and on screen),
     * RoadSense's overlay state and the learned range. Unknown is null.
     */
    private fun values(out: OutputStream) {
        val snap = reply { LauncherApiHandler.handle("GET", "/api/launcher/v1/summary", null, it) }
            ?.let { s -> try { GlassWidgetsFeed.parse(s) } catch (t: Throwable) { null } }
        val live = if (snap?.driving == true && !snap.reversing) {
            reply { GlassWidgetsApiHandler.handle("GET", "/api/widgets/live?dyn=1", null, it) }
                ?.let { l -> try { GlassWidgetsFeed.live(l) } catch (t: Throwable) { null } }
        } else null
        val roadState = try {
            UnifiedConfigManager.loadConfig().optJSONObject("roadSense")?.optJSONObject("overlayState")
        } catch (e: Exception) { null }
        val road = GlassWidgetsFeed.road(roadState, System.currentTimeMillis())
        val calibrated = snap?.let { s -> learnedRange()?.let { GlassWidgetsFeed.calibrated(it, s.phev) } }
        val m = CustomSignals.values(snap, road, live, calibrated)
        val values = JSONObject()
        for (k in m.keys.sorted()) {
            val v = m[k]
            values.put(k, if (v == null || (v is Double && !v.isFinite())) JSONObject.NULL else v)
        }
        HttpResponse.sendJson(out, JSONObject().put("success", true).put("at", System.currentTimeMillis())
            .put("values", values).toString())
    }

    /** A core route answered in this process, no socket: its JSON when it replied 200 without `success:false`. */
    private fun reply(route: (OutputStream) -> Boolean): JSONObject? {
        val buf = ByteArrayOutputStream()
        val handled = try { route(buf) } catch (t: Throwable) { false }
        if (!handled) return null
        val text = buf.toString("UTF-8")
        if (!text.startsWith("HTTP/1.1 200")) return null
        val o = try { JSONObject(text.substringAfter("\r\n\r\n")) } catch (e: Exception) { return null }
        return o.takeIf { !GlassWidgetsFeed.failed(it) }
    }

    /** `/api/trips/range` as its route builds it; null until trip analytics is up, or on an error reply. */
    private fun learnedRange(): JSONObject? = try {
        CameraDaemon.getTripAnalyticsManager()?.let { TripApiHandler(it).handleRequest("/api/trips/range", "GET", null, null) }
            ?.takeIf { it.optInt("_status", 200) == 200 && !GlassWidgetsFeed.failed(it) }
    } catch (t: Throwable) {
        null
    }

    private fun list(out: OutputStream) {
        val shown = CustomWidgetStore.shownIds().toHashSet()
        val widgets = JSONArray()
        for (e in CustomWidgetStore.list()) {
            widgets.put(JSONObject().put("id", e.id).put("doc", e.doc).put("updatedMs", e.updatedMs)
                .put("shown", e.id in shown).put("source", e.source)
                .put("communityId", e.communityId ?: JSONObject.NULL))
        }
        val limits = JSONObject().put("maxWidgets", CustomWidgetStore.MAX_WIDGETS)
            .put("maxBytes", CustomWidgetStore.MAX_BYTES)
        HttpResponse.sendJson(out, JSONObject().put("success", true).put("widgets", widgets)
            .put("limits", limits).toString())
    }

    private fun put(id: String, body: String?, out: OutputStream) {
        if (!CustomWidgetStore.isValidId(id)) return fail(out, 400, Messages.get("errors.widgets_invalid_id"))
        if (body != null && body.length > MAX_BODY_CHARS) return fail(out, 413, Messages.get("errors.widgets_too_big"))
        val doc = parse(body)?.optJSONObject("doc") ?: return fail(out, 400, Messages.get("errors.widgets_missing_doc"))
        val raw = doc.toString()
        val canonical = try {
            CustomDoc.canonical(raw)
        } catch (e: CustomDoc.Invalid) {
            // the engine refuses an oversize doc as invalid; that one is a 413
            val code = if (raw.toByteArray(Charsets.UTF_8).size > CustomWidgetStore.MAX_BYTES) 413 else 400
            return fail(out, code, e.message ?: Messages.get("errors.widgets_invalid"))
        } catch (e: Exception) {
            logger.warn("widget $id rejected: ${e.message}")
            return fail(out, 400, Messages.get("errors.widgets_invalid"))
        }
        val entry = try {
            CustomWidgetStore.edit(id, canonical)
        } catch (e: CustomWidgetStore.Full) {
            return fail(out, 409, e.message ?: Messages.get("errors.widgets_too_many"))
        } catch (e: CustomWidgetStore.TooLarge) {
            return fail(out, 413, e.message ?: Messages.get("errors.widgets_too_big"))
        } catch (e: CustomWidgetStore.Unavailable) {
            return fail(out, 503, e.message ?: Messages.get("errors.widgets_try_again"))
        } catch (e: IllegalArgumentException) {
            return fail(out, 400, e.message ?: Messages.get("errors.widgets_invalid"))
        }
        HttpResponse.sendJson(out, JSONObject().put("success", true).put("id", entry.id)
            .put("doc", entry.doc).toString())
    }

    private fun delete(id: String, out: OutputStream) {
        if (!CustomWidgetStore.isValidId(id)) return fail(out, 400, Messages.get("errors.widgets_invalid_id"))
        val removed = try {
            CustomWidgetStore.delete(id)
        } catch (e: CustomWidgetStore.Unavailable) {
            return fail(out, 503, e.message ?: Messages.get("errors.widgets_try_again"))
        }
        if (!removed) return fail(out, 404, Messages.get("errors.widgets_not_found"))
        HttpResponse.sendJson(out, JSONObject().put("success", true).put("id", id).toString())
    }

    private fun show(id: String, body: String?, out: OutputStream) {
        if (!CustomWidgetStore.isValidId(id)) return fail(out, 400, Messages.get("errors.widgets_invalid_id"))
        val shown = parse(body)?.opt("shown") as? Boolean ?: return fail(out, 400, Messages.get("errors.widgets_shown_boolean"))
        try {
            CustomWidgetStore.setShown(id, shown)
        } catch (e: NoSuchElementException) {
            return fail(out, 404, Messages.get("errors.widgets_not_found"))
        } catch (e: CustomWidgetStore.Unavailable) {
            return fail(out, 503, e.message ?: Messages.get("errors.widgets_try_again"))
        }
        HttpResponse.sendJson(out, JSONObject().put("success", true).put("id", id).put("shown", shown).toString())
    }

    private fun notAllowed(out: OutputStream) = fail(out, 405, Messages.get("errors.widgets_method_not_allowed"))

    private fun parse(body: String?): JSONObject? {
        if (body.isNullOrBlank()) return null
        return try { JSONObject(body) } catch (_: Exception) { null }
    }

    /** Never lets a failed write escape to HttpServer's socket-closing catch. */
    private fun fail(out: OutputStream, code: Int, message: String) {
        try {
            HttpResponse.sendJson(out, code, JSONObject().put("success", false).put("error", message).toString())
        } catch (_: Throwable) { }
    }
}
