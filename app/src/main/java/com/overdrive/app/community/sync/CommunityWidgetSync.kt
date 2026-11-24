package com.overdrive.app.community.sync

import com.overdrive.app.community.sync.CommunitySyncProvider.Result
import com.overdrive.app.logging.DaemonLogger
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Transport for the community Worker's declarative widget and dashboard catalogs.
 * Same client rules as [CommunitySyncProvider]: one proxy route per request and no redirects.
 * Validation lives in each document handler and the Worker.
 */
internal class CommunityWidgetSync(
    private val workerUrlSupplier: () -> String?,
    private val clientFactory: () -> OkHttpClient = { proxiedClient() },
    private val catalog: String = "widgets",
) {
    init {
        require(catalog == "widgets" || catalog == "dashboards")
    }

    /** Omitting viewerId enables the Worker's anonymous edge cache. */
    fun list(query: String, viewerId: String? = null): Result {
        val base = baseUrl() ?: return noUrl()
        val url = if (query.isEmpty()) "$base/$catalog" else "$base/$catalog?$query"
        return call(url, "GET", viewerId, null)
    }

    /** Returns the item and its validated recipe. A viewerId requests its per-publisher ownership flag. */
    fun get(id: String, viewerId: String? = null): Result {
        val base = baseUrl() ?: return noUrl()
        return call("$base/$catalog/${enc(id)}", "GET", viewerId, null)
    }

    /**
     * POST /widgets → `201 {id}`, or `200 {id, existing:true}` when this device already published [clientKey].
     * [fields] holds authorName, name, description, category and the daemon-canonicalised widget.
     */
    fun publish(deviceId: String, clientKey: String, fields: JSONObject): Result {
        val base = baseUrl() ?: return noUrl()
        return call("$base/$catalog", "POST", deviceId, withDevice(fields, deviceId).put("clientKey", clientKey))
    }

    /** PUT /widgets/{id} — partial update of a row this publisher owns; [fields] holds only what changes. */
    fun update(id: String, deviceId: String, fields: JSONObject): Result {
        val base = baseUrl() ?: return noUrl()
        return call("$base/$catalog/${enc(id)}", "PUT", deviceId, withDevice(fields, deviceId))
    }

    /** DELETE /widgets/{id} — publisher-match delete. */
    fun delete(id: String, deviceId: String): Result {
        val base = baseUrl() ?: return noUrl()
        return call("$base/$catalog/${enc(id)}", "DELETE", deviceId, JSONObject().put("deviceId", deviceId))
    }

    /** POST /widgets/{id}/rate — one vote per device, 1..5. */
    fun rate(id: String, deviceId: String, stars: Int): Result {
        val base = baseUrl() ?: return noUrl()
        return call("$base/$catalog/${enc(id)}/rate", "POST", deviceId,
            JSONObject().put("deviceId", deviceId).put("stars", stars))
    }

    /** POST /widgets/{id}/report — one flag per device. */
    fun report(id: String, deviceId: String, reason: String?): Result {
        val base = baseUrl() ?: return noUrl()
        val body = JSONObject().put("deviceId", deviceId)
        if (!reason.isNullOrEmpty()) body.put("reason", reason)
        return call("$base/$catalog/${enc(id)}/report", "POST", deviceId, body)
    }

    /** POST /widgets/{id}/install — best-effort download counter, given at most [INSTALL_TIMEOUT_S]. */
    fun install(id: String): Result {
        val base = baseUrl() ?: return noUrl()
        return call("$base/$catalog/${enc(id)}/install", "POST", null, JSONObject(), INSTALL_TIMEOUT_S)
    }

    private fun withDevice(fields: JSONObject, deviceId: String): JSONObject =
        JSONObject(fields.toString()).put("deviceId", deviceId)

    private fun call(url: String, method: String, deviceId: String?, payload: JSONObject?, timeoutS: Long = 0): Result = try {
        val b = Request.Builder().url(url).method(method, payload?.toString()?.toRequestBody(JSON))
        if (!deviceId.isNullOrEmpty()) b.header("X-Device-Id", deviceId)
        exec(b.build(), timeoutS)
    } catch (t: Throwable) {
        fail(t.message ?: "$method error")
    }

    /** A non-2xx still parses the body so the Worker's `{error}` reaches the user. */
    private fun exec(req: Request, timeoutS: Long): Result {
        val base = clientFactory()
        val client = if (timeoutS <= 0) base else base.newBuilder()
            .connectTimeout(timeoutS, TimeUnit.SECONDS)
            .readTimeout(timeoutS, TimeUnit.SECONDS)
            .callTimeout(timeoutS, TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { resp ->
            val parsed = readJson(resp.body)
            if (resp.isSuccessful) return Result(true, parsed, null, resp.code)
            val msg = parsed?.optString("error", "")?.ifEmpty { null } ?: "HTTP ${resp.code}"
            return Result(false, parsed, msg, resp.code)
        }
    }

    /** Bounded read: a body over [MAX_RESPONSE_BYTES] fails the call instead of filling the heap. */
    private fun readJson(body: ResponseBody?): JSONObject? {
        if (body == null) return null
        val source = body.source()
        if (body.contentLength() > MAX_RESPONSE_BYTES || source.request(MAX_RESPONSE_BYTES + 1)) {
            throw IOException("community response too large")
        }
        val raw = source.readUtf8()
        return try { JSONObject(raw) } catch (_: Throwable) { null }
    }

    private fun baseUrl(): String? {
        val u = workerUrlSupplier()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return u.trimEnd('/')
    }

    private fun noUrl(): Result = fail("no community worker URL configured")

    private fun fail(msg: String): Result {
        logger.warn("community $catalog call failed: $msg")
        return Result(false, null, msg, 0)
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private val logger = DaemonLogger.getInstance("Community/Widgets")
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Largest Worker response read (a full list page of 16 KB docs is under 1 MB). */
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        private const val INSTALL_TIMEOUT_S = 3L

        /** Same route policy as [CommunitySyncProvider]; no pooled sockets outlive a call. */
        private fun proxiedClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .proxySelector(com.overdrive.app.mqtt.ProxyHelper.singleRouteSelector())
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
