package com.overdrive.app.navmap.glass

import android.graphics.Color
import android.util.Log
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.light.Position
import java.util.WeakHashMap

/**
 * OpenFreeMap liberty set up for navigation, both themes (SPEC §3); night recolours liberty itself.
 * Call once per style load, before MapGlassLayers.install. Missing layer ids are skipped.
 */
object MapGlassBasemap {

    const val BUILDING_3D_LAYER_ID = "building-3d"
    const val BUILDING_3D_MIN_ZOOM = 14f
    private const val BUILDING_LAYER_ID = "building"
    private const val FALLBACK_BUILDING_3D = "roadsense-building-3d"
    private const val TAG = "MapGlassBasemap"

    private val HIDDEN = setOf(
        "poi_r1", "poi_r7", "poi_r20", "poi_transit", "road_one_way_arrow", "road_one_way_arrow_opposite",
        "highway-shield-non-us", "highway-shield-us-interstate", "road_shield_us", "natural_earth"
    )
    private val ROAD_NAMES = arrayOf("highway-name-path", "highway-name-minor", "highway-name-major")
    private const val SUBURB_LABEL_LAYER_ID = "label_other"
    private val GUIDING_FADED = arrayOf("landuse_residential", "park", "landcover_wood", "landcover_grass",
        "landuse_pitch", "landuse_track", "landuse_cemetery", "landuse_hospital", "landuse_school")
    private const val GUIDING_FILL_FACTOR = 0.6f
    // Theme opacity of each faded fill, per loaded style (read before the first fade).
    private val fillBase = WeakHashMap<Style, HashMap<String, Float>>()

    // Night table (SPEC §3); the Google night roadmap is styled from it too (GoogleMapStyle.nightStylesJson).
    internal const val N_LAND = "#0E1519"
    internal const val N_RESID = "#111A1F"
    internal const val N_PARK = "#0F1F1C"
    internal const val N_PARK_LINE = "#183029"
    internal const val N_WOOD = "#10211C"
    internal const val N_GRASS = "#11231F"
    internal const val N_WATER = "#0A2033"
    internal const val N_WATERWAY = "#0E2C45"
    internal const val N_AERO = "#151E23"
    internal const val N_PITCH = "#13201F"
    internal const val N_HOSP = "#1A1C21"
    internal const val N_SCHOOL = "#161D22"
    internal const val N_CASING = "#060B0E"
    // Roads stay brighter than the night buildings: minor 1.25:1 over low-rise, 2:1 on land.
    internal const val N_MINOR = "#3A4852"
    internal const val N_SERVICE = "#2C3740"
    internal const val N_LINK = "#3E4C56"
    internal const val N_SEC = "#44535D"
    internal const val N_PRIM = "#4C5C67"
    internal const val N_MWAY = "#52636E"
    internal const val N_PATH = "#2A343A"
    internal const val N_RAIL = "#29323A"
    internal const val N_BLD = "#18222A"
    internal const val N_BLD_LINE = "#202C34"
    internal const val N_BOUNDARY = "#3A4A52"
    internal const val N_LABEL = "#93A4AC"
    internal const val N_LABEL_MAJOR = "#B2C1C7"
    internal const val N_PLACE = "#A2B1B8"
    internal const val N_WATER_LABEL = "#5E87A7"
    internal const val N_TRANSIT = "#7FB4CF"
    internal const val N_HALO = "#0E1519"
    private const val DAY_HALO = "rgba(255,255,255,0.92)"

    /**
     * [lang]: the app's label language. "en" gives the SPEC coalesce (name:en, name_en, name:latin, name);
     * another language is tried first only where MapLibre can shape its script (Indic scripts fall back).
     */
    fun apply(style: Style, night: Boolean, lang: String = "en") {
        if (!loaded(style)) return
        val layers = try { style.layers } catch (_: Throwable) { return }
        val label = labelField(lang)
        for (layer in layers) {
            val id = try { layer.id } catch (_: Throwable) { continue }
            try {
                if (id in HIDDEN) {
                    layer.setProperties(PropertyFactory.visibility(Property.NONE))
                    continue
                }
                if (layer is SymbolLayer) symbol(layer, id, night, label)
                if (layer is LineLayer) roadWidth(layer, id)
                if (night) recolor(layer, id)
            } catch (t: Throwable) {
                Log.w(TAG, "$id: ${t.javaClass.simpleName}")
            }
        }
        try { buildings(style, night) } catch (t: Throwable) { Log.w(TAG, "building-3d: ${t.javaClass.simpleName}") }
        try { light(style, night) } catch (t: Throwable) { Log.w(TAG, "light: ${t.javaClass.simpleName}") }
        try { lowerRoadNames(style) } catch (t: Throwable) { Log.w(TAG, "road names: ${t.javaClass.simpleName}") }
    }

    /** Guiding: suburb names hide and land-use fills drop to 60 % of the theme's opacity; false restores both. Call after apply(). */
    fun setGuiding(style: Style, guiding: Boolean) {
        if (!loaded(style)) return
        try {
            style.getLayer(SUBURB_LABEL_LAYER_ID)?.setProperties(
                PropertyFactory.visibility(if (guiding) Property.NONE else Property.VISIBLE))
            val base = fillBase.getOrPut(style) { HashMap() }
            for (id in GUIDING_FADED) {
                val fill = style.getLayer(id) as? FillLayer ?: continue
                var opacity = base[id]
                if (opacity == null) {
                    val pv = fill.fillOpacity
                    if (pv.isExpression) continue
                    opacity = (pv.value as? Number)?.toFloat() ?: 1f
                    base[id] = opacity
                }
                fill.setProperties(PropertyFactory.fillOpacity(if (guiding) opacity * GUIDING_FILL_FACTOR else opacity))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "guiding: ${t.javaClass.simpleName}")
        }
    }

    private fun labelField(lang: String): Expression {
        val latin = Expression.coalesce(
            Expression.get("name:en"), Expression.get("name_en"), Expression.get("name:latin"), Expression.get("name")
        )
        val l = lang.trim().lowercase()
        if (l.isEmpty() || l == "en") return latin
        val key = "name:$l"
        return Expression.switchCase(
            Expression.all(Expression.has(key), Expression.isSupportedScript(Expression.toString(Expression.get(key)))),
            Expression.get(key),
            latin
        )
    }

    private fun symbol(layer: SymbolLayer, id: String, night: Boolean, label: Expression) {
        val tf = layer.textField
        val text = if (tf.isExpression) tf.expression?.toString() else tf.value?.toString()
        if (text != null && text.contains("name")) layer.setProperties(PropertyFactory.textField(label))
        when (id) {
            "highway-name-minor" -> layer.setProperties(PropertyFactory.textSize(zoomSize(14.0, 12.5, 16.0, 14.5, 18.0, 16.5)))
            "highway-name-major" -> layer.setProperties(PropertyFactory.textSize(zoomSize(13.0, 13.5, 16.0, 16.0, 18.0, 18.0)))
            "label_other" -> layer.setProperties(PropertyFactory.textSize(zoomSize(12.0, 11.0, 16.0, 13.0)))
        }
        if (night) {
            val color = when {
                id.startsWith("water") -> N_WATER_LABEL
                id == "poi_transit" -> N_TRANSIT
                id.startsWith("label_") -> N_PLACE
                id == "highway-name-major" -> N_LABEL_MAJOR
                else -> N_LABEL
            }
            layer.setProperties(
                PropertyFactory.textColor(color),
                PropertyFactory.textHaloColor(N_HALO),
                PropertyFactory.textHaloWidth(1.4f),
                PropertyFactory.textHaloBlur(0.4f)
            )
            if (id == "poi_transit") layer.setProperties(PropertyFactory.iconOpacity(0.75f))
        }
        // Street names follow the road but face the driver, so the drive camera's tilt doesn't flatten them.
        if (id in ROAD_NAMES) layer.setProperties(
            PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.textMaxAngle(30f),
            PropertyFactory.textHaloColor(if (night) N_HALO else DAY_HALO),
            PropertyFactory.textHaloWidth(1.6f)
        )
    }

    // First matching rule wins, as in the mock; paints not listed keep liberty's values.
    private fun recolor(layer: Layer, id: String) {
        val line = layer as? LineLayer
        val fill = layer as? FillLayer
        when {
            id == "background" -> (layer as? BackgroundLayer)?.setProperties(PropertyFactory.backgroundColor(N_LAND))
            id == "landuse_residential" -> fill?.setProperties(PropertyFactory.fillColor(N_RESID))
            id == "park" -> fill?.setProperties(
                PropertyFactory.fillColor(N_PARK), PropertyFactory.fillOutlineColor(N_PARK_LINE), PropertyFactory.fillOpacity(0.9f))
            id == "park_outline" -> line?.setProperties(PropertyFactory.lineColor(N_PARK_LINE))
            id == "landcover_wood" -> fill?.setProperties(PropertyFactory.fillColor(N_WOOD), PropertyFactory.fillOpacity(0.7f))
            id == "landcover_grass" -> fill?.setProperties(PropertyFactory.fillColor(N_GRASS), PropertyFactory.fillOpacity(0.6f))
            id == "landcover_ice" || id == "landcover_sand" -> fill?.setProperties(PropertyFactory.fillColor(N_AERO))
            id == "landcover_wetland" -> fill?.setProperties(PropertyFactory.fillOpacity(0.15f))
            id == "landuse_pitch" || id == "landuse_track" -> fill?.setProperties(PropertyFactory.fillColor(N_PITCH))
            id == "landuse_cemetery" -> fill?.setProperties(PropertyFactory.fillColor(N_PARK))
            id == "landuse_hospital" -> fill?.setProperties(PropertyFactory.fillColor(N_HOSP))
            id == "landuse_school" -> fill?.setProperties(PropertyFactory.fillColor(N_SCHOOL))
            id == "water" -> fill?.setProperties(PropertyFactory.fillColor(N_WATER))
            line != null && id.startsWith("waterway") -> line.setProperties(PropertyFactory.lineColor(N_WATERWAY))
            id == "aeroway_fill" -> fill?.setProperties(PropertyFactory.fillColor(N_AERO))
            id == "aeroway_runway" || id == "aeroway_taxiway" -> line?.setProperties(PropertyFactory.lineColor(N_LINK))
            id == "road_area_pattern" -> fill?.setProperties(PropertyFactory.fillOpacity(0.18f))
            line != null && id.endsWith("_casing") -> line.setProperties(PropertyFactory.lineColor(N_CASING))
            line != null && (id.contains("path") || id.contains("pedestrian")) -> line.setProperties(PropertyFactory.lineColor(N_PATH))
            line != null && (id.contains("major_rail") || id.contains("transit_rail")) -> line.setProperties(PropertyFactory.lineColor(N_RAIL))
            line != null && id.contains("service_track") -> line.setProperties(PropertyFactory.lineColor(N_SERVICE))
            line != null && (id.endsWith("_minor") || id.endsWith("_street")) -> line.setProperties(PropertyFactory.lineColor(N_MINOR))
            line != null && id.endsWith("_link") -> line.setProperties(PropertyFactory.lineColor(N_LINK))
            line != null && id.endsWith("secondary_tertiary") -> line.setProperties(PropertyFactory.lineColor(N_SEC))
            line != null && id.endsWith("trunk_primary") -> line.setProperties(PropertyFactory.lineColor(N_PRIM))
            line != null && id.endsWith("motorway") -> line.setProperties(PropertyFactory.lineColor(N_MWAY))
            id == BUILDING_LAYER_ID -> fill?.setProperties(PropertyFactory.fillColor(N_BLD), PropertyFactory.fillOutlineColor(N_BLD_LINE))
            line != null && id.startsWith("boundary") -> line.setProperties(PropertyFactory.lineColor(N_BOUNDARY))
        }
    }

    /** Install before the route ribbon so it can be drawn above the building volumes. */
    fun installBuildings(style: Style, night: Boolean, enabled: Boolean) {
        if (!loaded(style)) return
        // Liberty supplies its own extrusions. Other OpenMapTiles styles may only have footprints.
        if (style.getLayer(BUILDING_3D_LAYER_ID) == null && style.getLayer(FALLBACK_BUILDING_3D) == null &&
            style.getSource("openmaptiles") != null) {
            val layer = FillExtrusionLayer(FALLBACK_BUILDING_3D, "openmaptiles").apply {
                sourceLayer = "building"
                minZoom = BUILDING_3D_MIN_ZOOM
                setProperties(
                    PropertyFactory.fillExtrusionColor(if (night) "#2b2d36" else "hsl(35,8%,85%)"),
                    PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                    PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                    PropertyFactory.fillExtrusionOpacity(0.8f)
                )
            }
            if (style.getLayer(BUILDING_LAYER_ID) != null) style.addLayerAbove(layer, BUILDING_LAYER_ID)
            else style.addLayer(layer)
        }
        setBuildingsVisible(style, enabled)
    }

    fun setBuildingsVisible(style: Style, visible: Boolean) {
        if (!loaded(style)) return
        val visibility = PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)
        style.getLayer(BUILDING_3D_LAYER_ID)?.setProperties(visibility)
        style.getLayer(FALLBACK_BUILDING_3D)?.setProperties(visibility)
    }

    private fun buildings(style: Style, night: Boolean) {
        val b = style.getLayer(BUILDING_3D_LAYER_ID) as? FillExtrusionLayer ?: return
        val h = Expression.coalesce(Expression.get("render_height"), Expression.literal(0))
        val color = if (night) {
            Expression.interpolate(Expression.linear(), h,
                Expression.stop(0, Expression.color(0xFF2A3A45.toInt())),
                Expression.stop(20, Expression.color(0xFF30414D.toInt())),
                Expression.stop(60, Expression.color(0xFF3E5262.toInt())))
        } else {
            Expression.interpolate(Expression.linear(), h,
                Expression.stop(0, Expression.color(0xFFDCD9D6.toInt())),
                Expression.stop(30, Expression.color(0xFFD5D2CE.toInt())))
        }
        // Parts already extruded as building:part carry hide_3d; extruding both z-fights.
        b.setFilter(Expression.not(Expression.has("hide_3d")))
        b.setProperties(
            PropertyFactory.fillExtrusionVerticalGradient(true),
            PropertyFactory.fillExtrusionColor(color),
            // Opaque: below 1, MapLibre adds a depth pass for every extrusion.
            PropertyFactory.fillExtrusionOpacity(1.0f)
        )
    }

    // Viewport light from the driver's front-left, so walls facing the driver read lit.
    private fun light(style: Style, night: Boolean) {
        val l = style.light ?: return
        l.anchor = Property.ANCHOR_VIEWPORT
        l.position = Position(1.2f, 210f, 32f)
        l.setColor(if (night) 0xFFBBD3E4.toInt() else Color.WHITE)
        l.intensity = if (night) 0.34f else 0.42f
    }

    // Street names lie on the ground: under the buildings and the route ribbon, never across the route.
    private fun lowerRoadNames(style: Style) {
        if (style.getLayer(BUILDING_LAYER_ID) == null) return
        val order = style.layers.map { it.id }
        val building = order.indexOf(BUILDING_LAYER_ID)
        for (id in ROAD_NAMES) {
            val at = order.indexOf(id)
            if (at < 0 || at < building) continue
            val l = style.getLayer(id) ?: continue
            if (!style.removeLayer(l)) continue
            try {
                style.addLayerBelow(l, BUILDING_LAYER_ID)
            } catch (t: Throwable) {
                style.addLayer(l)
            }
        }
    }

    // Fill and casing width ramps (zoom, dp) by liberty road class; strictly ordered at every zoom, 26 dp motorway at z17.4.
    private val ROAD_WIDTHS: Map<String, Pair<Expression, Expression>> by lazy {
        val motorway = ramp(5.0, 0.0, 7.0, 1.0, 12.0, 3.6, 14.0, 6.5, 15.0, 10.0, 16.0, 15.0, 17.4, 26.0, 20.0, 80.0) to
            ramp(5.0, 0.4, 6.0, 0.7, 7.0, 1.75, 12.0, 4.6, 14.0, 8.0, 15.0, 12.2, 16.0, 17.4, 17.4, 29.0, 20.0, 85.0)
        val primary = ramp(5.0, 0.0, 7.0, 1.0, 12.0, 3.2, 14.0, 5.5, 15.0, 8.0, 16.0, 12.0, 17.4, 21.0, 20.0, 70.0) to
            ramp(5.0, 0.4, 6.0, 0.7, 7.0, 1.75, 12.0, 4.2, 14.0, 7.0, 15.0, 10.0, 16.0, 14.4, 17.4, 24.0, 20.0, 75.0)
        val secondary = ramp(6.5, 0.0, 8.0, 0.5, 12.0, 2.4, 14.0, 4.2, 15.0, 6.5, 16.0, 9.5, 17.4, 17.0, 20.0, 56.0) to
            ramp(8.0, 1.5, 12.0, 3.4, 14.0, 5.7, 15.0, 8.4, 16.0, 11.7, 17.4, 20.0, 20.0, 61.0)
        val minor = ramp(13.5, 0.0, 14.0, 2.5, 15.0, 4.5, 16.0, 7.0, 17.4, 13.0, 20.0, 44.0) to
            ramp(12.0, 0.5, 13.0, 1.0, 14.0, 4.0, 15.0, 6.4, 16.0, 9.2, 17.4, 15.5, 20.0, 49.0)
        val link = ramp(12.5, 0.0, 13.0, 1.5, 14.0, 2.5, 15.0, 4.0, 16.0, 6.0, 17.4, 10.5, 20.0, 34.0) to
            ramp(12.0, 1.0, 13.0, 3.0, 14.0, 4.0, 15.0, 5.9, 16.0, 8.2, 17.4, 13.0, 20.0, 39.0)
        val service = ramp(15.5, 0.0, 16.0, 3.0, 17.4, 6.5, 20.0, 20.0) to ramp(15.0, 1.0, 16.0, 5.0, 17.4, 8.5, 20.0, 24.0)
        mapOf("motorway" to motorway, "trunk_primary" to primary, "secondary_tertiary" to secondary, "minor" to minor,
            "street" to minor, "link" to link, "motorway_link" to link, "service_track" to service)
    }

    /** road_/bridge_/tunnel_ fills and their _casing layers; paths, rails and other lines keep liberty's widths. */
    private fun roadWidth(layer: LineLayer, id: String) {
        if (!(id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("tunnel_"))) return
        val rest = id.substringAfter('_')
        val casing = rest.endsWith("_casing")
        val w = ROAD_WIDTHS[rest.removeSuffix("_casing")] ?: return
        layer.setProperties(PropertyFactory.lineWidth(if (casing) w.second else w.first))
    }

    private fun ramp(vararg zw: Double): Expression {
        val stops = Array(zw.size / 2) { i -> Expression.stop(zw[2 * i], zw[2 * i + 1]) }
        return Expression.interpolate(Expression.exponential(1.5), Expression.zoom(), *stops)
    }

    private fun zoomSize(vararg zv: Double): Expression {
        val stops = Array(zv.size / 2) { i -> Expression.stop(zv[2 * i], zv[2 * i + 1]) }
        return Expression.interpolate(Expression.linear(), Expression.zoom(), *stops)
    }

    private fun loaded(s: Style): Boolean = try { s.isFullyLoaded } catch (_: Throwable) { false }
}
