package com.overdrive.app.navmap.glass

import android.graphics.Color
import com.overdrive.app.overlay.glass.GlassColor
import com.overdrive.app.overlay.glass.GlassTheme

/** Map overlay colours for one theme, derived from [GlassTheme] (dev/roadsense-map/03-GLASS-UI.md §Colours). */
class MapGlassPalette private constructor(val night: Boolean, navigator: Boolean = false) {

    val theme: GlassTheme = GlassTheme.of(night)

    /** Rim, body, core and glow of one ribbon (route or a congestion level). */
    class Ribbon(val rim: Int, val body: Int, val core: Int, val glow: Int)

    val route = if (navigator) {
        val casing = if (night) 0xff173631.toInt() else 0xffedfbf0.toInt()
        val line = if (night) 0xff77d3bc.toInt() else 0xff157f6d.toInt()
        Ribbon(casing, line, line, line)
    } else Ribbon(
        rim = if (night) GlassColor.darken(theme.accent, 0.55f) else GlassColor.darken(theme.accent, 0.42f),
        body = if (night) GlassColor.mix(theme.accent, theme.glowAccent, 0.5f) else theme.accent,
        core = if (night) GlassColor.lighten(theme.accent, 0.45f) else theme.glowAccent,
        glow = theme.glowAccent
    )
    val slow = Ribbon(
        rim = if (night) GlassColor.darken(theme.glowWarn, 0.5f) else GlassColor.darken(theme.warn, 0.2f),
        body = theme.glowWarn,
        core = if (night) theme.warn else GlassColor.lighten(theme.glowWarn, 0.35f),
        glow = theme.glowWarn
    )
    val jam = Ribbon(
        rim = if (night) GlassColor.darken(theme.glowDanger, 0.5f) else GlassColor.darken(theme.danger, 0.2f),
        body = theme.glowDanger,
        core = if (night) theme.danger else GlassColor.lighten(theme.glowDanger, 0.38f),
        glow = theme.glowDanger
    )
    /** Alternative routes in the route preview: a quiet neutral ribbon. */
    val alternate = Ribbon(
        rim = if (night) 0xFF1B2328.toInt() else 0xFF9AA6A1.toInt(),
        body = if (night) 0xFF5E6E72.toInt() else 0xFFC4CDC9.toInt(),
        core = if (night) 0xFF7F8F93.toInt() else 0xFFDDE4E1.toInt(),
        glow = if (night) 0xFF5E6E72.toInt() else 0xFFC4CDC9.toInt()
    )
    val specular = Color.WHITE
    val shadow: Int = theme.shadow

    val arrowRim: Int = if (night) theme.glassBot else theme.ink
    val arrowBody: Int = if (night) GlassColor.mix(Color.WHITE, theme.accent, 0.05f) else Color.WHITE

    /** Puck accent: mix(accent, glowAccent, .55). */
    val puck: Int = GlassColor.mix(theme.accent, theme.glowAccent, 0.55f)

    // Layer opacities that differ by theme.
    val routeShadowOpacity = if (navigator) 0f else if (night) 0.55f else 0.30f
    val specOpacity = if (navigator) 0f else if (night) 0.78f else 0.85f
    val trafficSpecOpacity = if (night) 0.75f else 0.85f
    val trafficGlowOpacity = if (night) 0.75f else 0.55f
    val arrowShadowOpacity = if (night) 0.60f else 0.34f

    companion object {
        private val NIGHT = MapGlassPalette(true)
        private val DAY = MapGlassPalette(false)
        fun of(night: Boolean): MapGlassPalette = if (night) NIGHT else DAY
        fun navigator(night: Boolean) = MapGlassPalette(night, navigator = true)

        /** "#RRGGBB" for paint properties set from a colour string. */
        fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)
    }
}
