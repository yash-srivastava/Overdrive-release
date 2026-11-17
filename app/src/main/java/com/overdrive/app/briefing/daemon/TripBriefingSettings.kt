package com.overdrive.app.briefing.daemon

import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject

/** The `tripBriefing` UnifiedConfig section. */
data class TripBriefingSettings(
    val enabled: Boolean = false,
    val voice: Boolean = true,
    val defaultApp: String = DEFAULT_APP,
    /** Range and hazard notes, worked out on the car. */
    val extras: Boolean = true,
    /** The rain note, which sends the car's position to the weather service. */
    val onlineExtras: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("voice", voice)
        .put("defaultApp", defaultApp)
        .put("extras", extras)
        .put("onlineExtras", onlineExtras)

    companion object {
        const val SECTION = "tripBriefing"
        const val DEFAULT_APP = "overdrive"

        @JvmField
        val APPS: Set<String> = setOf("overdrive", "waze", "gmaps", "car")

        private val FLAGS = listOf("enabled", "voice", "extras", "onlineExtras")

        /** Missing or mistyped keys read as their defaults. */
        @JvmStatic
        fun fromSection(o: JSONObject?): TripBriefingSettings {
            val d = TripBriefingSettings()
            if (o == null) return d
            return TripBriefingSettings(
                enabled = o.opt("enabled") as? Boolean ?: d.enabled,
                voice = o.opt("voice") as? Boolean ?: d.voice,
                defaultApp = (o.opt("defaultApp") as? String)?.takeIf { it in APPS } ?: d.defaultApp,
                extras = o.opt("extras") as? Boolean ?: d.extras,
                onlineExtras = o.opt("onlineExtras") as? Boolean ?: d.onlineExtras,
            )
        }

        /** Current settings; the defaults (off) when the config can't be read. */
        @JvmStatic
        fun load(): TripBriefingSettings = try {
            fromSection(UnifiedConfigManager.loadConfig().optJSONObject(SECTION))
        } catch (t: Throwable) {
            TripBriefingSettings()
        }

        /** Merges [patch] into the section (keys it lacks are kept); false when the write failed. */
        @JvmStatic
        fun save(patch: JSONObject): Boolean = try {
            UnifiedConfigManager.updateSection(SECTION, patch)
        } catch (t: Throwable) {
            false
        }

        /** The recognised keys of a POST /settings body, validated. */
        internal fun patchFrom(body: JSONObject): JSONObject {
            val out = JSONObject()
            for (k in FLAGS) {
                if (!body.has(k)) continue
                val v = body.opt(k) as? Boolean ?: throw BadRequest("$k must be true or false")
                out.put(k, v)
            }
            if (body.has("defaultApp")) out.put("defaultApp", BriefingJson.app(body.opt("defaultApp")))
            if (out.length() == 0) throw BadRequest("no settings given")
            return out
        }
    }
}
