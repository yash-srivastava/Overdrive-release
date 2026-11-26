package com.overdrive.app.community

import com.overdrive.app.automation.Automation
import com.overdrive.app.automation.Automations
import com.overdrive.app.byd.BydDataCollector
import com.overdrive.app.community.config.CommunityConfig
import com.overdrive.app.community.sync.CommunitySyncProvider
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.mqtt.VehicleControlCatalog
import com.overdrive.app.server.HttpResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * HTTP routes for the Community Automations feature (browse / publish / rate /
 * report / import shared automations). Runs in the DAEMON process (UID shell), so
 * it talks to the automation store ([Automations]) in-process and reaches the
 * open-source `community-edge/` Cloudflare backend through [CommunitySyncProvider]
 * (proxy-aware OkHttp). The WebView only ever calls these local /api/community
 * routes on loopback — it never talks to the cloud directly.
 *
 * Endpoints:
 *  - GET    /api/community/list[?sort&order&page&pageSize&q&category] → paginated catalog (proxied)
 *  - GET    /api/community/automation/{id}   → one shared automation + per-vehicle capability enrichment
 *  - POST   /api/community/publish           → publish a local automation ({rules,authorName,name,description,category,localId})
 *  - PUT    /api/community/automation/{id}   → update a shared automation I published (device-match)
 *  - POST   /api/community/import/{id}       → import a shared automation into the LOCAL store (disabled)
 *  - POST   /api/community/rate/{id}         → rate 1..5 ({stars})
 *  - POST   /api/community/report/{id}       → flag abuse ({reason?})
 *  - DELETE /api/community/automation/{id}   → delete a shared automation I published (device-match)
 *  - GET    /api/community/settings          → { workerUrl, authorName }
 *  - POST   /api/community/settings          → persist { workerUrl?, authorName? }
 *
 * ## Load-bearing safety (the app-side half of the guard stack; see community-edge/README.md)
 *  1. NO-SHELL on both publish AND import — an automation carrying a `shell` action
 *     is refused locally regardless of the shell gate, in addition to the Worker's
 *     server-side rejection. Triple defense (UI + here + Worker).
 *  2. IMPORT-AS-DISABLED — an imported automation is forced `disabled=true` so the
 *     user reviews its (human-readable) rules before it can fire. No silent activation.
 *  3. CAPABILITY CHECK — each action is probed against the current vehicle via
 *     [VehicleControlCatalog.isAvailable]; unsupported ones are surfaced (a badge)
 *     but do NOT block import (fail-open: the probe is advisory, import-disabled is
 *     the real net). Only catalog-backed vehicle controls are probeable; ApiAction /
 *     notification actions have no per-vehicle probe and are treated as available.
 *  4. Publish is SERVER-AUTHORITATIVE — the blob is re-parsed through
 *     [Automation.fromJson] and re-serialized ([Automation.toJson]) so a tampered
 *     client payload is normalized to the canonical, schema-valid form before upload.
 *  5. RE-PUBLISH DEDUPE — the backend POST is a CREATE, so publishing an automation that
 *     was already shared would add a duplicate catalog row. The local→remote row map
 *     ([CommunityConfig.publishedRowId], keyed by local automation id) promotes such a
 *     re-publish to an in-place update instead. Not a security gate — the Worker still
 *     owns ownership via the publisher-id match — purely duplicate prevention.
 */
object CommunityApiHandler {

    private val logger = DaemonLogger.getInstance("CommunityApiHandler")

    /** Wire format version of the automation blob (mirrors community-edge schema_version). */
    private const val SCHEMA_VERSION = 1

    /** Name clamp on import — mirrors the local editor's name field maxLength (automations.js). */
    private const val MAX_NAME_LEN = 64

    private const val PREFIX = "/api/community/"

    /** Lazily-built provider; the URL supplier force-reloads UCM so an app-side URL
     *  change is picked up without a daemon restart (cross-UID; user-initiated path). */
    private val provider: CommunitySyncProvider by lazy {
        CommunitySyncProvider({ CommunityConfig.snapshot(forceReload = true).workerUrl })
    }

    @JvmStatic
    fun handle(method: String, path: String, body: String?, out: OutputStream): Boolean {
        val q = path.indexOf('?')
        val pathOnly = if (q >= 0) path.substring(0, q) else path
        val query = if (q >= 0) path.substring(q + 1) else ""

        try {
            when {
                pathOnly == "${PREFIX}list" && method == "GET" -> return listCatalog(query, out)
                pathOnly == "${PREFIX}publish" && method == "POST" -> return publish(body, out)
                pathOnly == "${PREFIX}settings" && method == "GET" -> return getSettings(out)
                pathOnly == "${PREFIX}settings" && method == "POST" -> return saveSettings(body, out)

                pathOnly.startsWith("${PREFIX}automation/") && method == "GET" ->
                    return getOne(idFrom(pathOnly, "${PREFIX}automation/"), out)
                pathOnly.startsWith("${PREFIX}automation/") && method == "PUT" ->
                    return updateOwn(idFrom(pathOnly, "${PREFIX}automation/"), body, out)
                pathOnly.startsWith("${PREFIX}automation/") && method == "DELETE" ->
                    return deleteOwn(idFrom(pathOnly, "${PREFIX}automation/"), out)
                pathOnly.startsWith("${PREFIX}import/") && method == "POST" ->
                    return importAutomation(idFrom(pathOnly, "${PREFIX}import/"), out)
                pathOnly.startsWith("${PREFIX}rate/") && method == "POST" ->
                    return rate(idFrom(pathOnly, "${PREFIX}rate/"), body, out)
                pathOnly.startsWith("${PREFIX}report/") && method == "POST" ->
                    return report(idFrom(pathOnly, "${PREFIX}report/"), body, out)
            }
        } catch (t: Throwable) {
            // Never let an exception escape to HttpServer's socket-closing catch with no response.
            logger.error("community handler error: " + t.message)
            safeError(out, 500, "internal error")
            return true
        }
        return false
    }

    // ── Browse ───────────────────────────────────────────────────────────────

    /** GET list — forward the (already query-string-shaped) whitelisted params to the Worker.
     *  Passes our publisher id so the Worker flags this install's own rows (`mine`). */
    private fun listCatalog(query: String, out: OutputStream): Boolean {
        forward(provider.list(query, CommunityConfig.publisherId()), out)
        return true
    }

    /**
     * Small, read-only catalog surface for the local GenAI assistant.
     *
     * The Worker search is a literal substring match, so use one meaningful
     * term from the natural-language request, then fall back to the top-rated
     * page when it has no literal hit. Only public list metadata is returned;
     * rules are still fetched and imported through the existing guarded routes.
     */
    @JvmStatic
    fun searchForAssistant(query: String): JSONObject {
        val term = assistantSearchTerm(query)
        fun queryString(q: String): String {
            val base = "sort=rating&order=desc&page=1&pageSize=20"
            return if (q.isEmpty()) base else "$base&q=" +
                    java.net.URLEncoder.encode(q, "UTF-8")
        }

        var result = provider.list(
            queryString(term), CommunityConfig.publisherId())
        if (result.ok && term.isNotEmpty()
            && (result.body?.optJSONArray("items")?.length() ?: 0) == 0) {
            result = provider.list(
                queryString(""), CommunityConfig.publisherId())
        }

        val out = JSONObject()
        if (!result.ok || result.body == null) {
            return out.put("available", false)
                .put("error", result.error ?: "community catalog unavailable")
                .put("items", JSONArray())
        }

        val safe = JSONArray()
        val items = result.body.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            safe.put(JSONObject()
                .put("id", item.optString("id", ""))
                .put("name", item.optString("name", ""))
                .put("description", item.optString("description", ""))
                .put("category", item.optString("category", "other"))
                .put("authorName", item.optString("authorName", ""))
                .put("ratingAvg", item.optDouble("ratingAvg", 0.0))
                .put("ratingCount", item.optInt("ratingCount", 0))
                .put("downloadCount", item.optInt("downloadCount", 0))
                .put("createdMs", item.optLong("createdMs", 0L))
                .put("mine", item.optBoolean("mine", false)))
        }
        return out.put("available", true)
            .put("searchTerm", term)
            .put("items", safe)
    }

    private fun assistantSearchTerm(query: String): String {
        val stop = setOf(
            "automation", "automations", "community", "find", "search",
            "browse", "help", "please", "create", "make", "useful",
            "want", "need", "with", "when", "that", "this", "from",
            "into", "about", "overdrive", "could", "would", "should")
        return query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in stop }
            .maxByOrNull { it.length }
            ?.take(40)
            ?: ""
    }

    /**
     * GET one — fetch the full record (with rules) and ENRICH it with a per-vehicle
     * capability check so the UI can show a "works on your car" badge and list any
     * unsupported actions. Enrichment is advisory; the record is returned regardless.
     */
    private fun getOne(id: String?, out: OutputStream): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        val res = provider.get(id, CommunityConfig.publisherId())
        if (!res.ok || res.body == null) { forward(res, out); return true }
        val automation = res.body.optJSONObject("automation")
        if (automation != null) {
            val unsupported = unsupportedActions(automation.optJSONObject("rules"))
            automation.put("unsupportedActions", unsupported)
            automation.put("compatible", unsupported.length() == 0)
        }
        HttpResponse.sendJson(out, res.body.toString())
        return true
    }

    // ── Publish ────────────────────────────────────────────────────────────────

    /**
     * POST publish — body { rules, authorName, name, description?, category?, localId? }.
     * Server-authoritative: re-parse the rules through [Automation.fromJson] (the
     * canonical validator) and re-serialize, blocking any shell action, before upload.
     *
     * RE-PUBLISH IS AN UPDATE. Publishing is a CREATE on the backend (POST mints a new
     * row id), so re-publishing an automation this install already shared would insert a
     * duplicate catalog entry. When [localId] maps to a known community row
     * ([CommunityConfig.publishedRowId]) we therefore route to [updateOwn] instead, which
     * PUTs the same row (preserving its id / ratings / downloads). A stale mapping (the
     * row was deleted server-side) falls back to a real publish and is re-pointed at the
     * new row — [promote]=false marks that fallback so the two paths can't ping-pong if
     * clearing the stale mapping failed to persist.
     */
    private fun publish(body: String?, out: OutputStream, promote: Boolean = true): Boolean {
        val json = parseBody(body) ?: run { safeError(out, 400, "invalid JSON body"); return true }
        val rules = json.optJSONObject("rules")
        if (rules == null) { safeError(out, 400, "rules required"); return true }

        // Already published from this local automation → update that row, never insert a
        // second one. Handled before validation so updateOwn applies the identical gate.
        val localId = json.optString("localId", "").trim()
        val existingRow = if (promote) CommunityConfig.publishedRowId(localId) else null
        if (existingRow != null) {
            logger.info("Re-publish of local $localId → updating community row $existingRow in place")
            return updateOwn(existingRow, body, out, localId)
        }

        val submission = validateSubmission(json, rules, out) ?: return true

        // Ownership key = the STABLE community publisher id (NOT DeviceId.current, which
        // rotates every 30 days — see CommunityConfig.publisherId). This is what the
        // server stores as author_device and later matches on delete, so the author can
        // still remove their upload after a RoadSense id rotation.
        val publisherId = CommunityConfig.publisherId()
        val res = provider.publish(publisherId, submission.authorName, submission.name,
            submission.description, submission.category, submission.canonical, SCHEMA_VERSION, submission.bundledGroups)
        // Remember local→remote so a later re-publish of this automation UPDATES the row
        // it created instead of inserting a duplicate. Best-effort: a failed persist only
        // costs us the dedupe on the next publish, never the publish itself.
        val newRow = res.body?.optString("id", "")?.ifEmpty { null }
        if (res.ok && newRow != null && localId.isNotEmpty()) {
            try { CommunityConfig.setPublishedRowId(localId, newRow) } catch (t: Throwable) {
                logger.warn("could not remember published row $newRow for local $localId: ${t.message}")
            }
        }
        forward(res, out)
        return true
    }

    private class Submission(
        val authorName: String,
        val name: String,
        val description: String,
        val category: String,
        val canonical: JSONObject,
        val bundledGroups: JSONObject
    )

    /** Shared publish/update gate. A null result means an error response was sent. */
    private fun validateSubmission(json: JSONObject, rules: JSONObject, out: OutputStream): Submission? {
        // Authoritative validate + canonicalize (drops any junk the client added).
        val parsed = Automation.fromJson(rules)
        if (parsed == null) { safeError(out, 400, "automation does not match the schema"); return null }
        val canonical = parsed.toJson()
        if (containsShellAction(canonical)) {
            safeError(out, 403, "shell automations cannot be shared to the community catalog")
            return null
        }

        val name = json.optString("name", "").trim()
        if (name.isEmpty()) { safeError(out, 400, "name required"); return null }
        val description = json.optString("description", "").trim()
        // A description is REQUIRED so the community list always explains what a shared
        // automation does (mirrors the client-side gate; defense-in-depth).
        if (description.isEmpty()) { safeError(out, 400, "description required"); return null }
        var category = json.optString("category", "other").trim().lowercase()
        if (category.isEmpty()) category = "other"

        // Referenced action groups, validated + canonicalized through the SAME gate as the
        // automation (a group is a named action list). null return = a group failed the
        // no-shell/schema gate and sanitize wrote the error response.
        val bundledGroups = sanitizeBundledGroups(json.optJSONObject("actionGroups"), out)
            ?: return null

        // Author name: prefer the request, else the remembered value; persist it for next time.
        var authorName = json.optString("authorName", "").trim()
        if (authorName.isEmpty()) authorName = CommunityConfig.snapshot(forceReload = true).authorName ?: ""
        if (authorName.isEmpty()) { safeError(out, 400, "authorName required"); return null }
        CommunityConfig.setAuthorName(authorName)

        return Submission(authorName, name, description, category, canonical, bundledGroups)
    }

    /**
     * Validate + canonicalize the optional bundled action groups from a publish/update
     * body. Input shape {id:{name,actions}} (from the client's _collectActionGroups). Each
     * group's actions are re-parsed through [Automation.parseActionsPublic] (the same
     * validator the automation body uses) and re-serialized, and rejected if any carries a
     * shell action — a group must never smuggle shell into the catalog. Returns:
     *   - a canonical {id:{name,actions}} object (possibly empty) on success, OR
     *   - null after writing a 403/400 error to [out] when a group is invalid/shell.
     * A null/empty input yields an empty object (no groups bundled).
     */
    private fun sanitizeBundledGroups(groups: JSONObject?, out: OutputStream): JSONObject? {
        val result = JSONObject()
        if (groups == null || groups.length() == 0) return result
        val ids = groups.keys()
        while (ids.hasNext()) {
            val id = ids.next()
            val g = groups.optJSONObject(id) ?: continue
            val gName = g.optString("name", "").trim()
            val actionsJson = g.optJSONArray("actions")
            if (gName.isEmpty() || actionsJson == null || actionsJson.length() == 0) {
                safeError(out, 400, "bundled action group '$id' is empty or unnamed")
                return null
            }
            val parsedActions = try { Automation.parseActionsPublic(actionsJson) } catch (_: Throwable) { null }
            if (parsedActions == null || parsedActions.isEmpty()) {
                safeError(out, 400, "bundled action group '$id' does not match the schema")
                return null
            }
            val canonActions = JSONArray()
            for (a in parsedActions) canonActions.put(a.toJson())
            // A group's actions carry no top-level trigger/condition wrapper, so scan the
            // action array directly for shell (containsShellAction expects an automation
            // object with an "actions" array — wrap it).
            val wrapper = JSONObject().put("actions", canonActions)
            if (containsShellAction(wrapper)) {
                safeError(out, 403, "an action group cannot contain a shell action")
                return null
            }
            result.put(id, JSONObject().put("name", gName).put("actions", canonActions))
        }
        return result
    }

    /**
     * PUT automation/{id} — UPDATE an automation THIS install already published. Same
     * server-authoritative validation as [publish] (re-parse via Automation.fromJson,
     * block shell), then PUT to the Worker, which gates on the publishing-device match
     * and preserves the row's ratings/downloads + id. This is how a user edits a shared
     * automation (edit locally → Update) instead of delete-and-republish (which would
     * reset stars/downloads and mint a new id). A non-owner / unknown id gets the
     * Worker's 404 forwarded as-is.
     *
     * [mappedLocalId] is set only when [publish] promoted a re-publish to an update via
     * the local→remote map: a 404 then means the mapping is STALE (the row was deleted
     * server-side), so we drop it and fall back to a genuine publish rather than failing
     * the user's action.
     */
    private fun updateOwn(id: String?, body: String?, out: OutputStream, mappedLocalId: String? = null): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        val json = parseBody(body) ?: run { safeError(out, 400, "invalid JSON body"); return true }
        val rules = json.optJSONObject("rules") ?: run { safeError(out, 400, "rules required"); return true }

        val submission = validateSubmission(json, rules, out) ?: return true

        // Same stable publisher id as publish/delete — the Worker matches it against the
        // stored author_device, so only the original publisher can update the row.
        val publisherId = CommunityConfig.publisherId()
        val res = provider.update(id, publisherId, submission.authorName, submission.name,
            submission.description, submission.category, submission.canonical, SCHEMA_VERSION, submission.bundledGroups)

        // Stale mapping: the remembered row is gone (or no longer ours). Forget it and
        // publish afresh so the user's Publish still does something sensible. Only ever
        // taken on the publish-promoted path — a direct PUT keeps forwarding the 404.
        if (!res.ok && res.httpCode == 404 && mappedLocalId != null) {
            logger.warn("mapped community row $id is gone — re-publishing local $mappedLocalId")
            try { CommunityConfig.setPublishedRowId(mappedLocalId, null) } catch (_: Throwable) { }
            return publish(body, out, promote = false)
        }

        // An explicit PUT (the Edit button) also (re)asserts the mapping, so a later
        // re-publish of the same local automation updates this row instead of duplicating.
        val localId = mappedLocalId ?: json.optString("localId", "").trim().ifEmpty { null }
        if (res.ok && localId != null) {
            try { CommunityConfig.setPublishedRowId(localId, id) } catch (_: Throwable) { }
        }
        forward(res, out)
        return true
    }

    // ── Import ───────────────────────────────────────────────────────────────

    /**
     * POST import — fetch the shared automation's authoritative rules from the Worker,
     * refuse a shell action, carry its NAME across, force it disabled, and save it into
     * the LOCAL automation store (minting a fresh local UUID). Fires the best-effort
     * install counter.
     */
    private fun importAutomation(id: String?, out: OutputStream): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        val res = provider.get(id)
        if (!res.ok || res.body == null) { forward(res, out); return true }
        val automation = res.body.optJSONObject("automation")
        val rules = automation?.optJSONObject("rules")
        if (rules == null) { safeError(out, 502, "shared automation had no rules"); return true }

        // Never import a shell automation, regardless of the local shell gate.
        if (containsShellAction(rules)) {
            safeError(out, 403, "this automation contains a shell action and cannot be imported")
            return true
        }

        // Recreate any bundled action groups the automation references (call-by-reference,
        // so the "Run action group" action stores a groupId that must resolve locally).
        // Preserve the ORIGINAL group id so the reference still matches; skip an id that
        // already exists locally (never clobber the importer's own group of that id — and
        // if it's the same shared group re-imported, it's already present). Each group was
        // validated + no-shell-checked at publish, but re-check BOTH here: ActionGroups.save
        // only re-parses (it has no shell scan of its own), and the shell check above sees
        // only `rules` — so without the explicit scan below a tampered Worker payload could
        // land a shell action in the user's permanent group library via a bundled group.
        // Best-effort: a group that fails to save just leaves its action a no-op,
        // exactly the pre-bundling behaviour — never blocks the automation import.
        val groups = automation.optJSONObject("actionGroups")
        if (groups != null) {
            val gids = groups.keys()
            while (gids.hasNext()) {
                val gid = gids.next()
                try {
                    if (com.overdrive.app.automation.ActionGroups.exists(gid)) continue
                    val g = groups.optJSONObject(gid) ?: continue
                    if (scanActionsForShell(g.optJSONArray("actions"))) {
                        logger.warn("Import: bundled action group $gid contains a shell action — skipped")
                        continue
                    }
                    val saved = com.overdrive.app.automation.ActionGroups.save(gid, g)
                    if (saved == null) logger.warn("Import: bundled action group $gid rejected (invalid) — its action will no-op")
                } catch (t: Throwable) {
                    logger.warn("Import: failed to recreate action group $gid: ${t.message}")
                }
            }
        }

        // Carry the shared automation's NAME across. The catalog row keeps the name in its
        // own column and the Worker's canonical rules blob drops it (its allowlist is
        // structural only), so without this an imported automation lands unnamed and shows
        // as "Automation 1a2b3c4d" — the user can't tell what they just added. The ROW name
        // is what they saw in the catalog and what the publisher typed into the publish form,
        // so it wins over any name a blob happens to carry. Clamped to the editor's own
        // maxLength so a hostile/oversized backend value can't bloat the local store.
        // Only ever written on this freshly-fetched import blob — existing saved automations
        // are never touched, and an empty row name leaves the blob exactly as it was.
        val sharedName = automation.optString("name", "").trim()
        if (sharedName.isNotEmpty()) {
            rules.put("name", if (sharedName.length > MAX_NAME_LEN) sharedName.substring(0, MAX_NAME_LEN) else sharedName)
        }
        // Import-as-disabled: the user reviews the rules before enabling.
        rules.put("disabled", true)
        // A shared/manual marker must never turn import-as-disabled into an
        // explicitly runnable rule. The user can choose Manual only after review.
        rules.remove("manualOnly")
        // Never inherit the publisher's run stats — a fresh import has never fired here.
        rules.remove("triggerCount")
        rules.remove("lastTriggered")
        // Reuse the exact create path the automations API uses (validates + saves + mints UUID).
        if (!Automations.updateAutomation(null, rules)) {
            safeError(out, 400, "shared automation does not match this app's schema")
            return true
        }

        // Best-effort popularity counter — ignore the result.
        try { provider.install(id) } catch (_: Throwable) { }

        val resp = JSONObject().put("success", true).put("disabled", true)
        HttpResponse.sendJson(out, resp.toString())
        logger.info("Imported community automation $id into local store (disabled)")
        return true
    }

    // ── Rate / report / delete ─────────────────────────────────────────────────

    private fun rate(id: String?, body: String?, out: OutputStream): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        val json = parseBody(body) ?: run { safeError(out, 400, "invalid JSON body"); return true }
        val stars = json.optInt("stars", 0)
        if (stars < 1 || stars > 5) { safeError(out, 400, "stars must be 1..5"); return true }
        forward(provider.rate(id, CommunityConfig.publisherId(), stars), out)
        return true
    }

    private fun report(id: String?, body: String?, out: OutputStream): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        val reason = parseBody(body)?.optString("reason", "")?.trim()
        forward(provider.report(id, CommunityConfig.publisherId(), reason), out)
        return true
    }

    private fun deleteOwn(id: String?, out: OutputStream): Boolean {
        if (id.isNullOrBlank()) { safeError(out, 400, "missing id"); return true }
        // Delete uses the STABLE publisher id (the ownership key the row was published
        // under), so the author can still remove it after a RoadSense id rotation.
        val res = provider.delete(id, CommunityConfig.publisherId())
        // Drop the local→remote mapping for the removed row so a later publish of the
        // same local automation creates a fresh entry instead of PUTting a dead id.
        if (res.ok) {
            try { CommunityConfig.forgetPublishedRow(id) } catch (_: Throwable) { }
        }
        forward(res, out)
        return true
    }

    // ── Settings ────────────────────────────────────────────────────────────────

    private fun getSettings(out: OutputStream): Boolean {
        val snap = CommunityConfig.snapshot(forceReload = true)
        val resp = JSONObject()
            .put("success", true)
            .put("workerUrl", snap.workerUrl ?: "")
            .put("authorName", snap.authorName ?: "")
            // {localAutomationId: communityRowId} — lets the UI resolve "which local
            // automation is this published row" exactly, instead of guessing by name.
            .put("publishedIds", CommunityConfig.publishedIds(forceReload = true))
        HttpResponse.sendJson(out, resp.toString())
        return true
    }

    private fun saveSettings(body: String?, out: OutputStream): Boolean {
        val json = parseBody(body) ?: run { safeError(out, 400, "invalid JSON body"); return true }
        var ok = true
        if (json.has("authorName")) ok = CommunityConfig.setAuthorName(json.optString("authorName", "").trim()) && ok
        if (json.has("workerUrl")) ok = CommunityConfig.setWorkerUrl(json.optString("workerUrl", "").trim()) && ok
        val resp = JSONObject().put("success", ok)
        if (!ok) resp.put("error", "failed to persist community settings")
        HttpResponse.sendJson(out, resp.toString())
        return true
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Extract + URL-decode the id segment after [prefix]. */
    private fun idFrom(pathOnly: String, prefix: String): String? {
        val raw = pathOnly.substring(prefix.length)
        if (raw.isEmpty() || raw.contains('/')) return if (raw.isEmpty()) null else raw.substringBefore('/')
        return try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (_: Throwable) { raw }
    }

    private fun parseBody(body: String?): JSONObject? {
        if (body.isNullOrBlank()) return null
        return try { JSONObject(body) } catch (_: Throwable) { null }
    }

    /**
     * True if any action in the rules blob has type "shell" (the stored action `type`
     * == the action's label id; the shell action's id is exactly "shell"). Matched
     * case-insensitively / by substring, defensively, mirroring the Worker's check.
     */
    private fun containsShellAction(rules: JSONObject): Boolean {
        // Scan the primary actions AND the automation-level else branch, RECURSING into
        // every action's nested childActions/elseActions. A shell action buried inside a
        // loop / if / action-group child list must NOT bypass the no-shell wall — this
        // is a security guarantee, so the scan follows the full action tree, not just
        // the top level.
        return scanActionsForShell(rules.optJSONArray("actions")) ||
                scanActionsForShell(rules.optJSONArray("elseActions"))
    }

    /** Recursively true if any action (or nested child action) has a shell-ish type. */
    private fun scanActionsForShell(actions: org.json.JSONArray?): Boolean {
        if (actions == null) return false
        for (i in 0 until actions.length()) {
            val obj = actions.optJSONObject(i) ?: continue
            val type = obj.optString("type").trim().lowercase()
            if (type.contains("shell")) return true
            if (scanActionsForShell(obj.optJSONArray("childActions"))) return true
            if (scanActionsForShell(obj.optJSONArray("elseActions"))) return true
        }
        return false
    }

    /**
     * Label ids of actions the CURRENT vehicle does not support, probed via the
     * vehicle-control catalog. Only catalog-backed controls are probeable; other
     * action kinds (ApiAction / notification) have no per-vehicle probe, so they are
     * omitted (treated as available). Fail-open — this is advisory for a UI badge.
     */
    private fun unsupportedActions(rules: JSONObject?): JSONArray {
        val result = JSONArray()
        val actions = rules?.optJSONArray("actions") ?: return result
        val snap = try { BydDataCollector.getInstance().data } catch (_: Throwable) { null }
        for (i in 0 until actions.length()) {
            val type = actions.optJSONObject(i)?.optString("type")?.trim() ?: continue
            val entity = VehicleControlCatalog.get(type) ?: continue // not catalog-probeable
            if (!entity.isAvailable(snap)) result.put(type)
        }
        return result
    }

    /** Forward a provider [CommunitySyncProvider.Result] to the client verbatim. */
    private fun forward(res: CommunitySyncProvider.Result, out: OutputStream) {
        if (res.ok && res.body != null) {
            HttpResponse.sendJson(out, res.body.toString())
            return
        }
        // Map a transport failure (httpCode 0) to 502; otherwise pass the Worker's status.
        val code = if (res.httpCode in 400..599) res.httpCode else 502
        val err = JSONObject().put("success", false).put("error", res.error ?: "community backend error")
        HttpResponse.sendJson(out, code, err.toString())
    }

    /** Send an error without letting sendError's checked IOException escape the router. */
    private fun safeError(out: OutputStream, code: Int, message: String) {
        try {
            HttpResponse.sendJson(out, code, JSONObject().put("success", false).put("error", message).toString())
        } catch (_: Throwable) { }
    }
}
