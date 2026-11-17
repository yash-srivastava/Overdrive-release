package com.overdrive.app.ui.util

import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * Daemon calls behind the SafeKeep pill and actions on the native recordings list.
 * Blocking; call from a background thread.
 */
object SafeKeepClipClient {

    /** One clip's backup state as reported by /api/safekeep/clips. */
    data class ClipState(
        val state: String,
        val progress: Int,
        val reason: String?,
        val link: String?,
        val locked: Boolean
    ) {
        /** Still moving: the list keeps refreshing while any visible clip is in one of these. */
        val inFlight: Boolean
            get() = state == "uploading" || state == "queued" || state == "waiting"
    }

    /** [disabled] = SafeKeep was switched off since the status check (HTTP 409). */
    data class StatesResult(val states: Map<String, ClipState>, val disabled: Boolean)

    data class ActionResult(val ok: Boolean, val error: String?, val message: String?)

    const val MAX_FILES_PER_CALL = 200

    /** On, with at least one storage linked. Null when the daemon can't be reached or answers an error. */
    fun isActive(): Boolean? {
        val (code, json) = request("/api/safekeep/status", "GET", null) ?: return null
        if (code !in 200..299 || json == null) return null
        if (!json.optBoolean("enabled", false)) return false
        return (json.optJSONArray("destinations")?.length() ?: 0) > 0
    }

    fun clipStates(names: List<String>): StatesResult? {
        if (names.isEmpty()) return StatesResult(emptyMap(), false)
        val files = JSONArray()
        names.take(MAX_FILES_PER_CALL).forEach { files.put(it) }
        val (code, json) = request("/api/safekeep/clips", "POST", JSONObject().put("files", files))
            ?: return null
        if (code == 409) return StatesResult(emptyMap(), true)
        if (json == null || !json.optBoolean("success", false)) return null
        val raw = json.optJSONObject("states") ?: return StatesResult(emptyMap(), false)
        val out = HashMap<String, ClipState>(raw.length())
        val keys = raw.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val st = raw.optJSONObject(name) ?: continue
            out[name] = ClipState(
                state = st.optString("state", "none"),
                progress = st.optInt("progress", 0).coerceIn(0, 100),
                reason = st.optString("reason", "").ifEmpty { null },
                link = st.optString("link", "").takeIf { it.startsWith("https://") },
                locked = st.optBoolean("locked", false)
            )
        }
        return StatesResult(out, false)
    }

    fun uploadNow(name: String): ActionResult =
        action("/api/safekeep/upload", JSONObject().put("file", name).put("mobileOk", false))

    fun setLocked(name: String, locked: Boolean): ActionResult =
        action("/api/safekeep/lock", JSONObject().put("file", name).put("locked", locked))

    private fun action(path: String, body: JSONObject): ActionResult {
        val (_, json) = request(path, "POST", body) ?: return ActionResult(false, "unreachable", null)
        if (json == null) return ActionResult(false, "bad_response", null)
        return ActionResult(
            json.optBoolean("success", false),
            json.optString("error", "").ifEmpty { null },
            json.optString("message", "").ifEmpty { null }
        )
    }

    /** (status, parsed body or null). Null when the request itself failed. */
    private fun request(path: String, method: String, body: JSONObject?): Pair<Int, JSONObject?>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, method, 1500, 5000)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try {
                if (text.isEmpty()) null else JSONObject(text)
            } catch (_: Exception) {
                null
            }
            code to json
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
