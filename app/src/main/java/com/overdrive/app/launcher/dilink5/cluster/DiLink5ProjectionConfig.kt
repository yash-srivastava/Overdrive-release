package com.overdrive.app.launcher.dilink5.cluster

import org.json.JSONObject

/** Persisted DiLink 5 projection choices. All values are clamped before use. */
data class DiLink5ProjectionConfig(
    val transport: Transport,
    val target: Target,
    val widthPercent: Int,
    val heightPercent: Int,
    val xOffsetPercent: Int,
    val yOffsetPercent: Int,
    val contentScalePercent: Int,
    val directScalePercent: Int
) {
    enum class Transport(val wire: String) {
        AUTO("auto"), HOSTED("hosted"), DIRECT("direct"), FACTORY_MINI("factory_mini");

        companion object {
            fun parse(value: String?): Transport {
                if (value.isNullOrBlank()) return HOSTED
                return entries.firstOrNull {
                    it.wire == value.trim().lowercase()
                } ?: AUTO
            }
        }
    }

    enum class Target(val wire: String) {
        STANDARD("standard"), FULL("full");

        companion object {
            fun parse(value: String?): Target? = entries.firstOrNull {
                it.wire == value?.trim()?.lowercase()
            }
        }
    }

    /** A shell-only OEM display cannot host an app-UID overlay, even with a hosted preference. */
    fun initialTransport(appDisplayVisible: Boolean): Transport =
        if (appDisplayVisible && transport == Transport.HOSTED) Transport.HOSTED else Transport.DIRECT

    fun toValues(): Map<String, Any> = mapOf(
        KEY_TRANSPORT to transport.wire,
        KEY_TARGET to target.wire,
        KEY_WIDTH to widthPercent,
        KEY_HEIGHT to heightPercent,
        KEY_X to xOffsetPercent,
        KEY_Y to yOffsetPercent,
        KEY_CONTENT_SCALE to contentScalePercent,
        KEY_DIRECT_SCALE to directScalePercent
    )

    companion object {
        const val KEY_TRANSPORT = "di5ProjectionTransport"
        const val KEY_TARGET = "di5ProjectionTarget"
        const val KEY_WIDTH = "di5HostedWidthPercent"
        const val KEY_HEIGHT = "di5HostedHeightPercent"
        const val KEY_X = "di5HostedXOffsetPercent"
        const val KEY_Y = "di5HostedYOffsetPercent"
        const val KEY_CONTENT_SCALE = "di5HostedContentScalePercent"
        const val KEY_DIRECT_SCALE = "di5DirectScalePercent"
        const val LEGACY_PREFER_FULL = "di5PreferFullCluster"

        @JvmStatic
        fun from(projection: JSONObject?): DiLink5ProjectionConfig {
            val source = projection ?: JSONObject()
            val explicitTarget = Target.parse(source.optString(KEY_TARGET, null))
            val target = explicitTarget ?: if (source.has(LEGACY_PREFER_FULL)) {
                if (source.optBoolean(LEGACY_PREFER_FULL, false)) Target.FULL else Target.STANDARD
            } else {
                // Fresh configurations use the standard projection target.
                Target.STANDARD
            }
            return DiLink5ProjectionConfig(
                Transport.parse(source.optString(KEY_TRANSPORT, null)),
                target,
                source.optInt(KEY_WIDTH, 100).coerceIn(20, 100),
                source.optInt(KEY_HEIGHT, 100).coerceIn(20, 100),
                source.optInt(KEY_X, 50).coerceIn(0, 100),
                source.optInt(KEY_Y, 50).coerceIn(0, 100),
                source.optInt(KEY_CONTENT_SCALE, 100).coerceIn(50, 150),
                source.optInt(KEY_DIRECT_SCALE, 100).coerceIn(50, 150)
            )
        }

        @JvmStatic
        fun applyDefaults(projection: JSONObject) {
            val normalized = from(projection)
            normalized.toValues().forEach { (key, value) ->
                if (!projection.has(key)) projection.put(key, value)
            }
            if (!projection.has(Sealion7HdmiProjection.CONFIG_KEY)) {
                projection.put(Sealion7HdmiProjection.CONFIG_KEY, false)
            }
        }
    }
}
