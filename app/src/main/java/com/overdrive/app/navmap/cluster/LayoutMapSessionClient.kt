package com.overdrive.app.navmap.cluster

import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONObject

/** Called only on the map's IO executor; app-process statics cannot describe daemon sessions. */
object LayoutMapSessionClient {
    fun alive(generation: Long, expectedDisplay: Int, actualDisplay: Int): Boolean? {
        if (generation <= 0 || expectedDisplay <= 0 || expectedDisplay != actualDisplay) return false
        val status = read() ?: return null
        return matchesSession(status, generation, expectedDisplay, actualDisplay)
    }

    internal fun matchesSession(status: JSONObject, generation: Long,
                                expectedDisplay: Int, actualDisplay: Int): Boolean? {
        if (generation <= 0 || expectedDisplay <= 0 || expectedDisplay != actualDisplay) return false
        if (status.optLong("generation") != generation) return false
        if (status.optString("phase").equals("STARTING", true)) return null
        if (!status.optBoolean("active")) return false
        val slots = status.optJSONArray("slots") ?: return false
        return (0 until slots.length()).any {
            val slot = slots.optJSONObject(it)
            slot != null && slot.optInt("displayId", -1) == expectedDisplay &&
                slot.optString("package") == "com.overdrive.app"
        }
    }

    fun mapProjected(): Boolean {
        val status = read() ?: return false
        if (!status.optBoolean("active")) return false
        val slots = status.optJSONArray("slots") ?: return false
        return (0 until slots.length()).any {
            slots.optJSONObject(it)?.optString("package") == "com.overdrive.app"
        }
    }

    private fun read(): JSONObject? {
        val connection = try {
            DaemonHttpClient.open("/api/cluster/layouts/status", "GET", 1_500, 2_000)
        } catch (_: Exception) { return null }
        return try {
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().use {
                val buffer = CharArray(4096)
                val result = StringBuilder()
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    result.append(buffer, 0, n)
                    if (result.length > 65_536) return null
                }
                result.toString()
            }
            JSONObject(text).takeIf { it.optBoolean("success") }
        } catch (_: Exception) { null } finally { connection.disconnect() }
    }
}
