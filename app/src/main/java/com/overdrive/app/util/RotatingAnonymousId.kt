package com.overdrive.app.util

import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject
import java.util.UUID

/** Shared rotation mechanism; each caller owns its own section, keys and lifetime. */
internal class RotatingAnonymousId(
    private val section: String,
    private val idKey: String,
    private val mintedKey: String,
    private val rotationMs: Long,
    private val readSection: (String) -> JSONObject? = { UnifiedConfigManager.forceReload().optJSONObject(it) },
    private val writeSection: (String, JSONObject) -> Boolean = { name, data ->
        UnifiedConfigManager.updateSection(name, data)
    },
) {
    fun current(nowMs: Long, preloadedSection: JSONObject? = null): String {
        val data = preloadedSection ?: try {
            readSection(section)
        } catch (_: Throwable) { null }
        val existing = data?.optString(idKey, "")?.ifEmpty { null }
        val minted = data?.optLong(mintedKey, 0L) ?: 0L
        if (existing != null && (nowMs - minted) < rotationMs) return existing

        val fresh = UUID.randomUUID().toString()
        try {
            // Merge only the two identity keys; leave unrelated settings intact.
            writeSection(section, JSONObject().put(idKey, fresh).put(mintedKey, nowMs))
        } catch (_: Throwable) { /* if persist fails we'll just mint again next time */ }
        return fresh
    }
}
