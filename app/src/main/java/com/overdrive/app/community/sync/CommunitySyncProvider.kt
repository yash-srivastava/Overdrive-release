package com.overdrive.app.community.sync

import com.overdrive.app.logging.DaemonLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the open-source Community Automations backend (`community-edge/`,
 * Cloudflare Worker + D1). Direct sibling of
 * [com.overdrive.app.roadsense.sync.CloudflareEdgeSyncProvider]: plain HTTPS to a
 * user-configurable Worker URL, NO embedded secret, proxy-aware OkHttp so it works
 * behind the sing-box/Tailscale tunnel (same convention as ABRP / AppUpdater /
 * RoadSense sync). Daemon-side; blocking calls run off the request thread.
 *
 * ## Wire contract (must match community-edge/src/worker.ts exactly)
 * - `GET  /automations?sort&order&page&pageSize&q&category` → `{items,page,pageSize,total,totalPages}`
 *   (list items omit the `rules` blob to stay light).
 * - `GET  /automations/{id}` → `{automation:{…,rules:{triggers,conditions,delay,actions,disabled}}}`
 * - `POST /automations` `{deviceId,authorName,name,description?,category?,rules}` → `201 {id,status,actionTypes}`
 * - `POST /automations/{id}/rate` `{deviceId,stars}` → `{ok,ratingAvg,ratingCount}`
 * - `POST /automations/{id}/report` `{deviceId,reason?}` → `{ok,reporters,hidden}`
 * - `POST /automations/{id}/install` → `{ok}` (best-effort download counter)
 * - `DELETE /automations/{id}` `{deviceId}` → `{ok,deleted}`
 *
 * Every method returns a [Result] (never throws to the caller) so the daemon handler
 * can map failures to an HTTP error instead of a hung socket — offline-first, like
 * the RoadSense provider.
 *
 * IMPORTANT: this client does NOT enforce the no-shell / structural rules — the
 * Worker is the authoritative ingest wall, and the daemon handler does the app-side
 * publish-block + capability-check + import-as-disabled. This is purely transport.
 */
class CommunitySyncProvider(
    private val workerUrlSupplier: () -> String?,
    /** Proxy-aware client, rebuilt per call (proxy comes and goes with ACC/network
     *  state; community calls are user-initiated and infrequent). Injectable for tests. */
    private val clientFactory: () -> OkHttpClient = { proxiedClient() },
) {

    private val httpClient: OkHttpClient get() = clientFactory()

    /** A call result: [ok] plus either a parsed [body] (JSON) or an [error]/[httpCode]. */
    data class Result(
        val ok: Boolean,
        val body: JSONObject? = null,
        val error: String? = null,
        val httpCode: Int = 0,
    )

    /** GET /automations with the raw query string already assembled by the caller
     *  (the handler forwards the web page's whitelisted params verbatim). [viewerDeviceId],
     *  when set, is sent as X-Device-Id so the Worker can flag which rows are the
     *  caller's own (`mine`) — advisory UX only, never required. */
    fun list(query: String, viewerDeviceId: String? = null): Result {
        val base = baseUrl() ?: return fail("no community worker URL configured")
        val url = if (query.isEmpty()) "$base/automations" else "$base/automations?$query"
        return httpGet(url, viewerDeviceId)
    }

    /** GET /automations/{id} — full record including the rules blob. [viewerDeviceId] as in [list]. */
    fun get(id: String, viewerDeviceId: String? = null): Result {
        val base = baseUrl() ?: return fail("no community worker URL configured")
        return httpGet("$base/automations/${enc(id)}", viewerDeviceId)
    }

    /** POST /automations — publish. [rules] is the Automation.toJson() object.
     *  [actionGroups] (optional {id:{name,actions}}) carries the definitions of any action
     *  groups the automation references, so a downloader can recreate them; omitted when
     *  the automation uses no groups. */
    fun publish(
        deviceId: String,
        authorName: String,
        name: String,
        description: String,
        category: String,
        rules: JSONObject,
        schemaVersion: Int,
        actionGroups: JSONObject? = null,
    ): Result {
        val base = baseUrl() ?: return fail("no community worker URL configured")
        val payload = submissionPayload(deviceId, authorName, name, description,
            category, rules, schemaVersion, actionGroups)
        return post("$base/automations", deviceId, payload)
    }

    /** PUT /automations/{id} — author UPDATE of a published automation (device-match).
     *  Same payload shape as publish; the server preserves ratings/downloads + the id. */
    fun update(
        id: String,
        deviceId: String,
        authorName: String,
        name: String,
        description: String,
        category: String,
        rules: JSONObject,
        schemaVersion: Int,
        actionGroups: JSONObject? = null,
    ): Result {
        val base = baseUrl() ?: return fail("no community worker URL configured")
        val payload = submissionPayload(deviceId, authorName, name, description,
            category, rules, schemaVersion, actionGroups)
        return try {
            val req = Request.Builder()
                .url("$base/automations/${enc(id)}")
                .header("X-Device-Id", deviceId)
                .put(payload.toString().toRequestBody(JSON))
                .build()
            exec(req)
        } catch (t: Throwable) {
            fail(t.message ?: "update error")
        }
    }

    /** POST /automations/{id}/rate — stars 1..5. */
    fun rate(id: String, deviceId: String, stars: Int): Result {
        val base = baseUrl() ?: return fail("no community worker URL configured")
        return post("$base/automations/${enc(id)}/rate", deviceId, JSONObject().put("deviceId", deviceId).put("stars", stars))
    }

    /** POST /automations/{id}/report — flag abuse. */
    fun report(id: String, deviceId: String, reason: String?): Result {
        val base = baseUrl() ?: return fail("no community worker URL configured")
        val body = JSONObject().put("deviceId", deviceId)
        if (!reason.isNullOrEmpty()) body.put("reason", reason)
        return post("$base/automations/${enc(id)}/report", deviceId, body)
    }

    /** POST /automations/{id}/install — best-effort download counter bump. */
    fun install(id: String): Result {
        val base = baseUrl() ?: return fail("no community worker URL configured")
        return post("$base/automations/${enc(id)}/install", null, JSONObject())
    }

    /** DELETE /automations/{id} — author delete (publishing-device match). */
    fun delete(id: String, deviceId: String): Result {
        val base = baseUrl() ?: return fail("no community worker URL configured")
        return try {
            val req = Request.Builder()
                .url("$base/automations/${enc(id)}")
                .header("X-Device-Id", deviceId)
                .delete(JSONObject().put("deviceId", deviceId).toString().toRequestBody(JSON))
                .build()
            exec(req)
        } catch (t: Throwable) {
            fail(t.message ?: "delete error")
        }
    }

    private fun submissionPayload(
        deviceId: String, authorName: String, name: String, description: String,
        category: String, rules: JSONObject, schemaVersion: Int, actionGroups: JSONObject?,
    ): JSONObject {
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("authorName", authorName)
            .put("name", name)
            .put("description", description)
            .put("category", category)
            .put("schemaVersion", schemaVersion)
            .put("rules", rules)
        if (actionGroups != null && actionGroups.length() > 0) payload.put("actionGroups", actionGroups)
        return payload
    }

    // ── HTTP plumbing ────────────────────────────────────────────────────────

    private fun httpGet(url: String, viewerDeviceId: String? = null): Result = try {
        val b = Request.Builder().url(url).get()
        if (!viewerDeviceId.isNullOrEmpty()) b.header("X-Device-Id", viewerDeviceId)
        exec(b.build())
    } catch (t: Throwable) {
        fail(t.message ?: "GET error")
    }

    private fun post(url: String, deviceId: String?, payload: JSONObject): Result = try {
        val b = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON))
        if (deviceId != null) b.header("X-Device-Id", deviceId)
        exec(b.build())
    } catch (t: Throwable) {
        fail(t.message ?: "POST error")
    }

    /** Execute a request and map it to a [Result]. A non-2xx still parses the body so
     *  the Worker's `{error:...}` message reaches the user (e.g. the no-shell rejection). */
    private fun exec(req: Request): Result {
        httpClient.newCall(req).execute().use { resp ->
            return communityResponse(resp.body?.string(), resp.isSuccessful, resp.code)
        }
    }

    /** Normalized base URL (trailing slash stripped), or null if unset/blank. */
    private fun baseUrl(): String? = communityBaseUrl(workerUrlSupplier())

    private fun fail(msg: String): Result {
        logger.warn("community call failed: $msg")
        return Result(false, null, msg, 0)
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private val logger = DaemonLogger.getInstance("Community/Sync")
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Privacy-sensitive community traffic takes one route per request, never a direct fallback. */
        private fun proxiedClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .proxySelector(com.overdrive.app.mqtt.ProxyHelper.singleRouteSelector())
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** Shared Worker result mapping; callers retain their own response-body read policy. */
internal fun communityResponse(raw: String?, successful: Boolean, code: Int): CommunitySyncProvider.Result {
    val parsed = raw?.let {
        try { JSONObject(it) } catch (_: Throwable) { null }
    }
    if (successful) return CommunitySyncProvider.Result(true, parsed, null, code)
    val message = parsed?.optString("error", "")?.ifEmpty { null } ?: "HTTP $code"
    return CommunitySyncProvider.Result(false, parsed, message, code)
}

internal fun communityBaseUrl(url: String?): String? =
    url?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/')
