package com.overdrive.app.community

import com.overdrive.app.server.Messages
import com.overdrive.app.community.config.CommunityConfig
import com.overdrive.app.community.sync.CommunitySyncProvider
import com.overdrive.app.community.sync.CommunityWidgetSync
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.overlay.glass.CustomDoc
import com.overdrive.app.server.HttpResponse
import com.overdrive.app.util.ScratchRoot
import com.overdrive.app.widgets.CustomWidgetStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * `/api/community/widgets/...` — browse, publish, rate, report and import shared glass widgets via
 * the community Worker. Publish uploads only the local store's doc after [CustomDoc.canonical];
 * import stores a canonical copy as `source:"community"` and never shows it on the car.
 */
object CommunityWidgetsApiHandler {

    private val logger = DaemonLogger.getInstance("CommunityWidgets")

    private const val PREFIX = "/api/community/widgets/"

    private const val MAX_NAME_LEN = 60
    private const val MAX_DESC_LEN = 500
    private const val MAX_AUTHOR_LEN = 40
    private const val MAX_REASON_LEN = 200
    private const val MAX_QUERY_LEN = 80
    private const val MAX_ERROR_LEN = 200
    private const val MAX_PAGE_SIZE = 50
    private const val MAX_BODY_CHARS = 4 * CustomWidgetStore.MAX_BYTES
    private const val MAX_OWN_ROWS = 64
    private const val LINK_FAILED = "Couldn't link the widget — try again"

    private val CATEGORIES = setOf("battery", "charging", "driving", "trip", "tyres", "road", "other")
    private val SORTS = setOf("updated", "created", "downloads", "rating")
    private val REMOTE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    /** The Worker's keyset cursor: base64url, at most 512 chars. */
    private val CURSOR = Regex("[A-Za-z0-9_-]{1,512}")

    /** One lock per local widget, so a slow publish of one widget never holds up another. */
    private val locks = ConcurrentHashMap<String, Any>()
    private val importLock = Any()
    private val rowsLock = Any()

    private val sync: CommunityWidgetSync by lazy {
        CommunityWidgetSync({ CommunityConfig.snapshot(forceReload = true).workerUrl })
    }

    /** A request the handler refuses; answered as `{success:false, error}` with [code]. */
    private class Rejected(val code: Int, message: String) : Exception(message)

    private class Canon(val json: String, val obj: JSONObject)

    private enum class Claim { OK, GONE, FAILED }

    @JvmStatic
    fun handle(method: String, path: String, body: String?, out: OutputStream): Boolean {
        val q = path.indexOf('?')
        val pathOnly = if (q >= 0) path.substring(0, q) else path
        if (!pathOnly.startsWith(PREFIX)) return false
        val rest = pathOnly.substring(PREFIX.length)
        val query = if (q >= 0) path.substring(q + 1) else ""

        try {
            when {
                rest == "list" && method == "GET" -> list(query, out)
                rest == "publish" && method == "POST" -> publish(body, out)
                rest.startsWith("item/") && method == "GET" -> getItem(idFrom(rest, "item/"), out)
                rest.startsWith("item/") && method == "PUT" -> updateItem(idFrom(rest, "item/"), body, out)
                rest.startsWith("item/") && method == "DELETE" -> deleteItem(idFrom(rest, "item/"), out)
                rest.startsWith("rate/") && method == "POST" -> rate(idFrom(rest, "rate/"), body, out)
                rest.startsWith("report/") && method == "POST" -> report(idFrom(rest, "report/"), body, out)
                rest.startsWith("import/") && method == "POST" -> importWidget(idFrom(rest, "import/"), out)
                else -> return false
            }
        } catch (r: Rejected) {
            reject(out, r.code, r.message ?: "Rejected")
        } catch (t: Throwable) {
            logger.error("community widgets handler error: " + t.message)
            reject(out, 500, Messages.get("errors.widgets_internal_error"))
        }
        return true
    }

    // ── Browse ───────────────────────────────────────────────────────────────

    /** GET list — forwards only allowlisted params; rows whose widget fails validation are dropped. */
    private fun list(query: String, out: OutputStream) {
        val p = parseQuery(query)
        val fwd = ArrayList<String>()
        p["sort"]?.trim()?.lowercase()?.takeIf { it in SORTS }?.let { fwd += "sort=$it" }
        p["q"]?.let { clamp(it, MAX_QUERY_LEN) }?.takeIf { it.isNotEmpty() }?.let { fwd += "q=" + enc(it) }
        p["category"]?.trim()?.lowercase()?.takeIf { it in CATEGORIES }?.let { fwd += "category=$it" }
        val cursor = p["cursor"]?.trim().orEmpty()
        if (cursor.isNotEmpty()) {
            if (!CURSOR.matches(cursor)) throw Rejected(400, Messages.get("errors.community_widgets_invalid_cursor"))
            fwd += "cursor=$cursor"
        }
        p["pageSize"]?.trim()?.toIntOrNull()?.let { fwd += "pageSize=" + it.coerceIn(1, MAX_PAGE_SIZE) }

        val body = okBody(sync.list(fwd.joinToString("&")))
        val rows = body.optJSONArray("items") ?: JSONArray()
        val mine = mineIds()
        val items = JSONArray()
        for (i in 0 until rows.length()) {
            val item = toItem(rows.optJSONObject(i) ?: continue, mine) ?: continue
            items.put(item)
        }
        reply(out, JSONObject()
            .put("success", true)
            .put("items", items)
            .put("nextCursor", cursorOf(body)))
    }

    private fun getItem(id: String, out: OutputStream) {
        val row = rowOf(okBody(sync.get(id)))
        val item = toItem(row, mineIds()) ?: throw Rejected(400, Messages.get("errors.community_widgets_unusable"))
        reply(out, JSONObject().put("success", true).put("item", item))
    }

    // ── Publish / update / delete ────────────────────────────────────────────

    /** POST publish — the doc always comes from the local store; a linked local widget updates its row. */
    private fun publish(body: String?, out: OutputStream) {
        val json = parseBody(body)
        val localId = str(json, "localId")
        if (!CustomWidgetStore.isValidId(localId)) throw Rejected(400, Messages.get("errors.widgets_invalid_id"))

        val result = synchronized(lockFor(localId)) {
            val entry = CustomWidgetStore.get(localId) ?: throw Rejected(404, Messages.get("errors.widgets_not_found"))
            val canon = canonicalise(entry.doc)
            val name = clamp(str(json, "name"), MAX_NAME_LEN).ifEmpty { clamp(str(canon.obj, "name"), MAX_NAME_LEN) }
            if (name.isEmpty()) throw Rejected(400, Messages.get("errors.community_widgets_name_required"))
            val description = clamp(str(json, "description"), MAX_DESC_LEN)
                .ifEmpty { clamp(str(canon.obj, "about"), MAX_DESC_LEN) }
            val typed = clamp(str(json, "authorName"), MAX_AUTHOR_LEN)
            val remembered = rememberedAuthor()
            val author = typed.ifEmpty { clamp(remembered, MAX_AUTHOR_LEN) }
            if (author.isEmpty()) throw Rejected(400, Messages.get("errors.community_widgets_author_required"))
            rejectLinks(name, description, author)
            val fields = JSONObject().put("authorName", author).put("name", name).put("description", description)
                .put("category", category(str(json, "category"))).put("widget", canon.obj)
            val publisherId = CommunityConfig.publisherId()

            // Only a widget made here updates its linked row; an imported copy always gets a row of its own. A linked
            // row that is gone or not ours is replaced, the old link kept until the new row exists.
            val linked = entry.communityId?.takeIf { entry.source == CustomWidgetStore.SOURCE_LOCAL && REMOTE_ID.matches(it) }
            if (linked != null) {
                val res = sync.update(linked, publisherId, fields)
                if (res.ok) {
                    if (claim(localId, linked) == Claim.FAILED) throw Rejected(503, LINK_FAILED)
                    addOwnRow(linked)
                    rememberAuthor(typed, remembered)
                    return@synchronized published(localId, linked, updated = true)
                }
                if (!goneOrNotOurs(res)) upstream(res)
                removeOwnRow(linked)
            }
            val created = okBody(sync.publish(publisherId, localId, fields))
            val id = str(created, "id")
            if (!REMOTE_ID.matches(id)) throw Rejected(502, Messages.get("errors.community_widgets_no_id"))
            when (claim(localId, id)) {
                Claim.OK -> {}
                Claim.GONE -> { discard(id, publisherId); throw Rejected(404, Messages.get("errors.widgets_not_found")) }
                Claim.FAILED -> { discard(id, publisherId); throw Rejected(503, LINK_FAILED) }
            }
            addOwnRow(id)
            // A repeat of an earlier attempt returns that row as it was then; bring it up to date.
            if (created.optBoolean("existing", false)) ensureOk(sync.update(id, publisherId, fields))
            rememberAuthor(typed, remembered)
            published(localId, id, updated = false)
        }
        reply(out, result)
    }

    /** PUT item/{id} — partial edit of a row this install published; only the fields sent change. */
    private fun updateItem(id: String, body: String?, out: OutputStream) {
        val json = parseBody(body)
        val localId = str(json, "localId")
        if (localId.isNotEmpty() && !CustomWidgetStore.isValidId(localId)) throw Rejected(400, Messages.get("errors.widgets_invalid_id"))
        val fields = JSONObject()
        if (json.has("name")) {
            val name = clamp(str(json, "name"), MAX_NAME_LEN)
            if (name.isEmpty()) throw Rejected(400, Messages.get("errors.community_widgets_name_required"))
            fields.put("name", name)
        }
        if (json.has("description")) fields.put("description", clamp(str(json, "description"), MAX_DESC_LEN))
        if (json.has("category")) fields.put("category", category(str(json, "category")))
        val typed = clamp(str(json, "authorName"), MAX_AUTHOR_LEN)
        val remembered = rememberedAuthor()
        if (json.has("authorName")) {
            val author = typed.ifEmpty { clamp(remembered, MAX_AUTHOR_LEN) }
            if (author.isEmpty()) throw Rejected(400, Messages.get("errors.community_widgets_author_required"))
            fields.put("authorName", author)
        }
        rejectLinks(str(fields, "name"), str(fields, "description"), str(fields, "authorName"))

        if (localId.isEmpty()) {
            applyUpdate(id, localId, fields)
        } else {
            synchronized(lockFor(localId)) { applyUpdate(id, localId, fields) }
        }
        rememberAuthor(typed, remembered)
        reply(out, JSONObject().put("success", true).put("id", id))
    }

    private fun applyUpdate(id: String, localId: String, fields: JSONObject) {
        if (localId.isNotEmpty()) {
            val entry = CustomWidgetStore.get(localId) ?: throw Rejected(404, Messages.get("errors.widgets_not_found"))
            fields.put("widget", canonicalise(entry.doc).obj)
        }
        if (fields.length() == 0) throw Rejected(400, Messages.get("errors.community_widgets_nothing_to_update"))
        ensureOk(sync.update(id, CommunityConfig.publisherId(), fields))
        addOwnRow(id)
        if (localId.isNotEmpty() && claim(localId, id) == Claim.FAILED) throw Rejected(503, LINK_FAILED)
    }

    /** DELETE item/{id} — the Worker's own 404/403 means the row is gone or not ours; this car stops claiming it either way. */
    private fun deleteItem(id: String, out: OutputStream) {
        val res = sync.delete(id, CommunityConfig.publisherId())
        if (!res.ok && !goneOrNotOurs(res)) upstream(res)
        removeOwnRow(id)
        try {
            for (e in CustomWidgetStore.list()) {
                if (e.communityId != id) continue
                // Re-checked under the widget's lock: a publish running now may already have moved the link.
                synchronized(lockFor(e.id)) {
                    if (CustomWidgetStore.get(e.id)?.communityId == id) CustomWidgetStore.setCommunityId(e.id, null)
                }
            }
        } catch (t: Throwable) {
            logger.warn("could not unlink local widgets from deleted row $id: ${t.message}")
        }
        reply(out, JSONObject().put("success", true))
    }

    // ── Rate / report ────────────────────────────────────────────────────────

    private fun rate(id: String, body: String?, out: OutputStream) {
        val v = (parseBody(body).opt("stars") as? Number)?.toDouble()
        if (v == null || v != Math.floor(v) || v < 1 || v > 5) throw Rejected(400, Messages.get("errors.community_widgets_stars_range"))
        val res = okBody(sync.rate(id, CommunityConfig.publisherId(), v.toInt()))
        reply(out, JSONObject()
            .put("success", true)
            .put("ratingAvg", rating(res))
            .put("ratingCount", res.optInt("ratingCount", 0).coerceAtLeast(0)))
    }

    private fun report(id: String, body: String?, out: OutputStream) {
        val json = if (body.isNullOrBlank()) JSONObject() else try { parseBody(body) } catch (_: Rejected) { JSONObject() }
        val reason = clamp(str(json, "reason"), MAX_REASON_LEN)
        val res = okBody(sync.report(id, CommunityConfig.publisherId(), reason.ifEmpty { null }))
        reply(out, JSONObject().put("success", true).put("hidden", res.optBoolean("hidden", false)))
    }

    // ── Import ───────────────────────────────────────────────────────────────

    /** POST import/{id} — canonical copy into the local store, not shown; a repeat import is a no-op. */
    private fun importWidget(id: String, out: OutputStream) {
        val row = rowOf(okBody(sync.get(id)))
        val doc = JSONObject((docOf(row) ?: throw Rejected(502, Messages.get("errors.community_widgets_no_content"))).toString())
        // The catalog row's name is what the user picked, so it names the local copy.
        val sharedName = clamp(str(row, "name"), MAX_NAME_LEN)
        if (sharedName.isNotEmpty() && !isLink(sharedName)) doc.put("name", sharedName)
        val canon = canonicalise(doc)

        var fresh = false
        val localId = synchronized(importLock) {
            existingImport(id, canon.obj) ?: CustomWidgetStore.newId().also {
                store(it, canon.json, id)
                fresh = true
            }
        }
        reply(out, JSONObject().put("success", true).put("localId", localId))
        if (fresh) {
            logger.info("Imported community widget $id as local $localId")
            // After the reply and capped at a few seconds: the counter never delays an import.
            sync.install(id)
        }
    }

    private fun store(localId: String, canonical: String, rowId: String) {
        try {
            CustomWidgetStore.put(localId, canonical, CustomWidgetStore.SOURCE_COMMUNITY, rowId)
        } catch (e: CustomWidgetStore.Full) {
            throw Rejected(409, e.message ?: "Too many widgets")
        } catch (e: CustomWidgetStore.TooLarge) {
            throw Rejected(413, e.message ?: "This widget is too big to save")
        } catch (e: CustomWidgetStore.Unavailable) {
            throw Rejected(503, e.message ?: "Try again")
        }
    }

    /** Local id of an earlier import of [rowId] carrying the identical doc. */
    private fun existingImport(rowId: String, doc: JSONObject): String? {
        val wanted = doc.toString()
        return CustomWidgetStore.list().firstOrNull {
            it.source == CustomWidgetStore.SOURCE_COMMUNITY && it.communityId == rowId && it.doc.toString() == wanted
        }?.id
    }

    // ── Validation ───────────────────────────────────────────────────────────

    /** [CustomDoc.canonical] is the authority; any `tap`, links and oversize are refused on top of it. */
    private fun canonicalise(doc: JSONObject): Canon {
        val raw = doc.toString()
        if (utf8Size(raw) > CustomWidgetStore.MAX_BYTES) throw Rejected(413, Messages.get("errors.community_widgets_too_big"))
        val canonical = try {
            CustomDoc.canonical(raw)
        } catch (e: CustomDoc.Invalid) {
            throw Rejected(400, clamp(e.message ?: Messages.get("errors.widgets_invalid"), MAX_ERROR_LEN))
        } catch (_: Throwable) {
            throw Rejected(400, Messages.get("errors.widgets_invalid"))
        }
        val obj = JSONObject(canonical)
        val json = if (stripTap(obj)) obj.toString() else canonical
        if (hasLink(obj)) throw Rejected(400, Messages.get("errors.community_widgets_no_links_in_widgets"))
        if (utf8Size(json) > CustomWidgetStore.MAX_BYTES) throw Rejected(413, Messages.get("errors.community_widgets_too_big"))
        return Canon(json, obj)
    }

    private fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** Removes every `tap` key (v1 widgets carry no actions); true when anything was removed. */
    private fun stripTap(node: Any?): Boolean {
        var removed = false
        when (node) {
            is JSONObject -> {
                if (node.has("tap")) { node.remove("tap"); removed = true }
                val keys = node.keys()
                while (keys.hasNext()) if (stripTap(node.opt(keys.next()))) removed = true
            }
            is JSONArray -> for (i in 0 until node.length()) if (stripTap(node.opt(i))) removed = true
        }
        return removed
    }

    /** True when any key or string anywhere in the tree fails [isLink]. */
    private fun hasLink(node: Any?): Boolean = when (node) {
        is String -> isLink(node)
        is JSONObject -> {
            val keys = node.keys()
            var found = false
            while (!found && keys.hasNext()) {
                val k = keys.next()
                found = isLink(k) || hasLink(node.opt(k))
            }
            found
        }
        is JSONArray -> (0 until node.length()).any { hasLink(node.opt(it)) }
        else -> false
    }

    /** The links rule shared with the builder and the Worker (NFKC, invisible marks dropped, `://`, `www.`, `javascript:`). */
    private fun isLink(s: String): Boolean = s.isNotEmpty() && CustomDoc.hasLink(s)

    private fun rejectLinks(vararg texts: String) {
        if (texts.any { isLink(it) }) throw Rejected(400, Messages.get("errors.community_widgets_no_links"))
    }

    /** Rows this car published: the registry plus rows linked from entries made here, so browse needs no device id. */
    private fun mineIds(): Set<String> {
        val ids = HashSet<String>(ownRows())
        for (e in CustomWidgetStore.list()) {
            if (e.source == CustomWidgetStore.SOURCE_LOCAL) e.communityId?.let { ids.add(it) }
        }
        return ids
    }

    // ── Published-row registry ───────────────────────────────────────────────

    /** Row ids this car published, newest last, so `mine` outlives the local widget; read per call, never cached. */
    private fun ownRows(): MutableList<String> {
        val f = rowsFile()
        if (!f.exists()) return ArrayList()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("rows") ?: JSONArray()
            (0 until arr.length()).mapNotNullTo(ArrayList()) { i -> (arr.opt(i) as? String)?.takeIf { REMOTE_ID.matches(it) } }
        } catch (e: Exception) {
            logger.warn("published community rows unreadable: ${e.message}")
            ArrayList()
        }
    }

    private fun addOwnRow(id: String) = editOwnRows { rows ->
        if (id in rows) false else {
            rows.add(id)
            while (rows.size > MAX_OWN_ROWS) rows.removeAt(0)
            true
        }
    }

    private fun removeOwnRow(id: String) = editOwnRows { rows -> rows.remove(id) }

    /** Read-modify-write under one lock, tmp + fsync + rename; best-effort, as the local link still marks a live widget's row. */
    private fun editOwnRows(edit: (MutableList<String>) -> Boolean) {
        synchronized(rowsLock) {
            val rows = ownRows()
            if (!edit(rows)) return
            val f = rowsFile()
            val tmp = File(f.path + ".tmp")
            try {
                f.parentFile?.mkdirs()
                FileOutputStream(tmp, false).use { o ->
                    o.write(JSONObject().put("rows", JSONArray(rows)).toString().toByteArray(Charsets.UTF_8))
                    o.fd.sync()
                }
                if (!tmp.renameTo(f)) throw IOException("rename failed")
            } catch (e: Exception) {
                logger.warn("could not save published community rows: ${e.message}")
            } finally {
                tmp.delete()
            }
        }
    }

    private fun rowsFile() = File(ScratchRoot.path("overdrive_community_widget_rows.json"))

    /** Spec-shaped item for one Worker row, or null when its id, text or widget does not validate here. */
    private fun toItem(row: JSONObject, mine: Set<String>): JSONObject? {
        val id = str(row, "id")
        if (!REMOTE_ID.matches(id)) return null
        val authorName = clamp(str(row, "authorName"), MAX_AUTHOR_LEN)
        val name = clamp(str(row, "name"), MAX_NAME_LEN)
        val description = clamp(str(row, "description"), MAX_DESC_LEN)
        if (isLink(authorName) || isLink(name) || isLink(description)) return null
        val widget = try { canonicalise(docOf(row) ?: return null).obj } catch (_: Throwable) { return null }
        return JSONObject()
            .put("id", id)
            .put("authorName", authorName)
            .put("name", name)
            .put("description", description)
            .put("category", category(str(row, "category")))
            .put("widget", widget)
            .put("ratingAvg", rating(row))
            .put("ratingCount", row.optInt("ratingCount", 0).coerceAtLeast(0))
            .put("downloads", row.optInt("downloads", 0).coerceAtLeast(0))
            .put("createdMs", row.optLong("createdMs", 0L))
            .put("updatedMs", row.optLong("updatedMs", 0L))
            .put("mine", id in mine)
    }

    /** The row of a `GET /widgets/{id}` response (`{item}`). */
    private fun rowOf(body: JSONObject): JSONObject =
        body.optJSONObject("item") ?: throw Rejected(502, Messages.get("errors.community_widgets_no_widget"))

    private fun docOf(row: JSONObject): JSONObject? = row.optJSONObject("widget")

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun lockFor(localId: String): Any = locks.getOrPut(localId) { Any() }

    private fun rememberedAuthor(): String = CommunityConfig.snapshot(forceReload = true).authorName ?: ""

    /** A typed author name is remembered only once the publish it came with has gone through. */
    private fun rememberAuthor(typed: String, remembered: String) {
        if (typed.isEmpty() || typed == remembered) return
        try { CommunityConfig.setAuthorName(typed) } catch (_: Throwable) { }
    }

    private fun published(localId: String, id: String, updated: Boolean): JSONObject {
        logger.info("Published local widget $localId as community row $id (updated=$updated)")
        return JSONObject().put("success", true).put("id", id).put("updated", updated)
    }

    /**
     * Links [localId] to row [rowId] this car owns and marks it made here (source local), which is what `mine`
     * reads. One retry; GONE when the local widget was deleted meanwhile.
     */
    private fun claim(localId: String, rowId: String): Claim {
        for (attempt in 1..2) {
            try {
                val entry = CustomWidgetStore.get(localId) ?: return Claim.GONE
                when {
                    entry.source != CustomWidgetStore.SOURCE_LOCAL ->
                        CustomWidgetStore.put(localId, entry.doc.toString(), CustomWidgetStore.SOURCE_LOCAL, rowId)
                    entry.communityId != rowId -> CustomWidgetStore.setCommunityId(localId, rowId)
                }
                val now = CustomWidgetStore.get(localId) ?: return Claim.GONE
                if (now.communityId == rowId && now.source == CustomWidgetStore.SOURCE_LOCAL) return Claim.OK
            } catch (t: Throwable) {
                logger.warn("could not link local widget $localId to community row $rowId (try $attempt): ${t.message}")
            }
        }
        return Claim.FAILED
    }

    /** Best-effort removal of a row this call created but could not link, so it is not left orphaned. */
    private fun discard(rowId: String, publisherId: String) {
        val res = sync.delete(rowId, publisherId)
        if (!res.ok) logger.warn("could not remove unlinked community row $rowId: ${res.error}")
    }

    /** The Worker's own JSON answer that a row is gone or not ours; a 403 without it came from something else. */
    private fun goneOrNotOurs(res: CommunitySyncProvider.Result): Boolean =
        (res.httpCode == 404 || res.httpCode == 403) && res.body?.opt("error") is String

    private fun category(raw: String): String = raw.trim().lowercase().let { if (it in CATEGORIES) it else "other" }

    private fun rating(o: JSONObject): Double =
        o.optDouble("ratingAvg", 0.0).let { if (it.isFinite()) it.coerceIn(0.0, 5.0) else 0.0 }

    private fun cursorOf(body: JSONObject): Any {
        val c = body.opt("nextCursor") as? String
        return if (c != null && CURSOR.matches(c)) c else JSONObject.NULL
    }

    /** The single decoded id segment after [prefix]; anything else is a 400. */
    private fun idFrom(rest: String, prefix: String): String {
        val raw = rest.substring(prefix.length)
        val id = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Throwable) { raw }
        if (!REMOTE_ID.matches(id)) throw Rejected(400, Messages.get("errors.community_widgets_invalid_id"))
        return id
    }

    private fun parseQuery(query: String): Map<String, String> {
        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            val v = if (eq >= 0) pair.substring(eq + 1) else ""
            try { params[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8") } catch (_: Throwable) { }
        }
        return params
    }

    private fun parseBody(body: String?): JSONObject {
        if (body.isNullOrBlank()) throw Rejected(400, Messages.get("errors.community_widgets_invalid_json"))
        if (body.length > MAX_BODY_CHARS) throw Rejected(413, Messages.get("errors.community_widgets_request_too_large"))
        return try { JSONObject(body) } catch (_: Throwable) { throw Rejected(400, Messages.get("errors.community_widgets_invalid_json")) }
    }

    /** String value of [key], trimmed; non-strings (including JSON null) read as empty. */
    private fun str(o: JSONObject, key: String): String = (o.opt(key) as? String)?.trim() ?: ""

    private fun clamp(s: String, max: Int): String {
        val t = s.trim()
        if (t.length <= max) return t
        return t.substring(0, if (Character.isHighSurrogate(t[max - 1])) max - 1 else max)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** The Worker's JSON body on success; otherwise a [Rejected] carrying its status. */
    private fun okBody(res: CommunitySyncProvider.Result): JSONObject {
        if (res.ok && res.body != null) return res.body
        upstream(res)
    }

    private fun ensureOk(res: CommunitySyncProvider.Result) {
        if (!res.ok) upstream(res)
    }

    /** Transport failures map to 502; 401/403/407 too, so the page never reads them as its own session ending. */
    private fun upstream(res: CommunitySyncProvider.Result): Nothing = when (res.httpCode) {
        401, 403, 407 -> throw Rejected(502, Messages.get("errors.community_widgets_refused"))
        in 400..599 -> throw Rejected(res.httpCode, clamp(res.error ?: Messages.get("errors.community_widgets_server_error"), MAX_ERROR_LEN))
        else -> throw Rejected(502, clamp(res.error ?: Messages.get("errors.community_widgets_server_error"), MAX_ERROR_LEN))
    }

    private fun reply(out: OutputStream, json: JSONObject) {
        HttpResponse.sendJson(out, json.toString())
    }

    private fun reject(out: OutputStream, code: Int, message: String) {
        try {
            HttpResponse.sendJson(out, code, JSONObject().put("success", false).put("error", message).toString())
        } catch (_: Throwable) { }
    }
}
