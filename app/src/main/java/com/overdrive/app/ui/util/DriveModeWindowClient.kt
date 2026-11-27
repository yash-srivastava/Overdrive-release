package com.overdrive.app.ui.util

import com.overdrive.app.camera.DiLink100Platform
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONObject

/**
 * App side of the BYD100F drive mode window (#1002). The daemon owns the work (it changes the
 * freeform settings and moves OverDrive's task), so this only talks to its endpoint.
 * Every call blocks on network I/O: call it off the main thread.
 */
object DriveModeWindowClient {

    private const val PATH = "/api/vehicle/drive-mode-window"

    /** Current status, or null when the daemon can't be reached. */
    fun status(): JSONObject? = request("GET", null)

    /** Turn the option on or off; returns the new status, or null on failure. */
    fun setEnabled(enabled: Boolean): JSONObject? =
        request("POST", JSONObject().put("enabled", enabled).toString())

    /** Tell the daemon the main screen is in front, so it can float it. No-op unless opted in. */
    fun notifyResumed() {
        if (!DiLink100Platform.isByd100f() || !isEnabledLocally()) return
        try {
            val conn = DaemonHttpClient.open("$PATH/resumed", "POST", 2000, 3000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write("{}".toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {
            // Best effort: the daemon also re-checks on every gear change.
        }
    }

    private fun isEnabledLocally(): Boolean = try {
        UnifiedConfigManager.loadConfig().optJSONObject("driveModeWindow")
            ?.optBoolean("enabled", false) == true
    } catch (_: Throwable) {
        false
    }

    private fun request(method: String, body: String?): JSONObject? = try {
        // Enabling runs several settings commands on the daemon; allow for them.
        val conn = DaemonHttpClient.open(PATH, method, 3000, 20000)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val text = if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
        conn.disconnect()
        text?.let { JSONObject(it) }?.takeIf { it.optBoolean("success", false) }
    } catch (_: Exception) {
        null
    }

    /** Which status line to show for a daemon status. */
    enum class Status { OFF, FLOATING, WAITING, NEEDS_RESTART, ERROR }

    fun statusOf(result: JSONObject?): Status {
        if (result == null) return Status.ERROR
        if (!result.optBoolean("enabled", false)) return Status.OFF
        val last = result.optString("lastResult", "")
        return when {
            last == "floating" -> Status.FLOATING
            last == "not_honoured" || last == "apply_failed" || last == "settings_unconfirmed"
                || !result.optBoolean("freeformAvailable", true) -> Status.NEEDS_RESTART
            else -> Status.WAITING
        }
    }
}
