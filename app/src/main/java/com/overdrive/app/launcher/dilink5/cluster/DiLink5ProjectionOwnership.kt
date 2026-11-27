package com.overdrive.app.launcher.dilink5.cluster

import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject

/** Durable identities retained until cleanup is confirmed or the boot changes. */
data class DiLink5ProjectionOwnership(
    val generation: Long,
    val bootId: String,
    val transport: String,
    val packageName: String,
    val physicalDisplayId: Int,
    val physicalDisplayName: String,
    val physicalUniqueId: String,
    val contentDisplayId: Int,
    val virtualDisplayName: String,
    val taskId: Int,
    val overlayOwned: Boolean,
    val virtualDisplayOwned: Boolean,
    val taskMayBeMoved: Boolean,
    val densityOwned: Boolean,
    val freeformOwned: Boolean,
    val compositorOwned: Boolean,
    val uncertain: Boolean,
    val densityOwner: String = "",
    val sealion7Hdmi: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("generation", generation)
        .put("bootId", bootId)
        .put("transport", transport)
        .put("package", packageName)
        .put("physicalDisplayId", physicalDisplayId)
        .put("physicalDisplayName", physicalDisplayName)
        .put("physicalUniqueId", physicalUniqueId)
        .put("contentDisplayId", contentDisplayId)
        .put("virtualDisplayName", virtualDisplayName)
        .put("taskId", taskId)
        .put("overlayOwned", overlayOwned)
        .put("virtualDisplayOwned", virtualDisplayOwned)
        .put("taskMayBeMoved", taskMayBeMoved)
        .put("densityOwned", densityOwned)
        .put("freeformOwned", freeformOwned)
        .put("compositorOwned", compositorOwned)
        .put("uncertain", uncertain)
        .put("densityOwner", densityOwner)
        .apply { if (sealion7Hdmi) put("sealion7Hdmi", true) }

    fun stablePhysicalIdentityMatches(id: Int, name: String, uniqueId: String): Boolean {
        if (id != physicalDisplayId || name != physicalDisplayName) return false
        return physicalUniqueId.isNotEmpty() && physicalUniqueId == uniqueId
    }

    fun mayClear(cleanupConfirmed: Boolean, currentBootId: String): Boolean =
        cleanupConfirmed || (bootId.isNotEmpty() && currentBootId.isNotEmpty() &&
            bootId != currentBootId)

    companion object {
        private const val SECTION = "projection"
        private const val KEY = "di5ProjectionOwnership"
        private val lock = Any()

        @JvmStatic
        fun load(): DiLink5ProjectionOwnership? = synchronized(lock) {
            loadAll().maxByOrNull { it.generation }
        }

        @JvmStatic
        fun load(generation: Long): DiLink5ProjectionOwnership? = synchronized(lock) {
            loadAll().firstOrNull { it.generation == generation }
        }

        @JvmStatic
        fun loadAll(): List<DiLink5ProjectionOwnership> = synchronized(lock) {
            val raw = UnifiedConfigManager.loadConfig().optJSONObject(SECTION)?.optJSONObject(KEY)
                ?: return@synchronized emptyList()
            fromJson(raw)?.let { return@synchronized listOf(it) }
            val values = ArrayList<DiLink5ProjectionOwnership>()
            val keys = raw.keys()
            while (keys.hasNext()) fromJson(raw.optJSONObject(keys.next()))?.let(values::add)
            values
        }

        @JvmStatic
        fun persist(value: DiLink5ProjectionOwnership): Boolean = synchronized(lock) {
            val records = JSONObject()
            loadAll().forEach { records.put(it.generation.toString(), it.toJson()) }
            records.put(value.generation.toString(), value.toJson())
            UnifiedConfigManager.updateSection(SECTION, JSONObject().put(KEY, records))
        }

        @JvmStatic
        fun clear(generation: Long, cleanupConfirmed: Boolean, currentBootId: String): Boolean =
            synchronized(lock) {
                val current = load(generation) ?: return@synchronized true
                if (!current.mayClear(cleanupConfirmed, currentBootId)) return@synchronized false
                val records = JSONObject()
                loadAll().filter { it.generation != generation }
                    .forEach { records.put(it.generation.toString(), it.toJson()) }
                UnifiedConfigManager.updateSection(
                    SECTION,
                    JSONObject().put(KEY, if (records.length() == 0) JSONObject.NULL else records)
                )
            }

        @JvmStatic
        fun fromJson(value: JSONObject?): DiLink5ProjectionOwnership? {
            val source = value ?: return null
            val generation = source.optLong("generation", -1L)
            val transport = source.optString("transport", "")
            val physicalId = source.optInt("physicalDisplayId", -1)
            val contentId = source.optInt("contentDisplayId", -1)
            if (generation <= 0 || transport !in setOf("hosted", "direct") ||
                physicalId <= 0 || contentId <= 0) return null
            return DiLink5ProjectionOwnership(
                generation,
                source.optString("bootId", ""),
                transport,
                source.optString("package", ""),
                physicalId,
                source.optString("physicalDisplayName", ""),
                source.optString("physicalUniqueId", ""),
                contentId,
                source.optString("virtualDisplayName", ""),
                source.optInt("taskId", -1),
                source.optBoolean("overlayOwned", false),
                source.optBoolean("virtualDisplayOwned", false),
                source.optBoolean("taskMayBeMoved", false),
                source.optBoolean("densityOwned", false),
                source.optBoolean("freeformOwned", false),
                source.optBoolean("compositorOwned", false),
                source.optBoolean("uncertain", false),
                source.optString("densityOwner", ""),
                transport == "hosted" && source.opt("sealion7Hdmi") == true,
            )
        }
    }
}
