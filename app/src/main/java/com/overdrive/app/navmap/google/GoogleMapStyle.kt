package com.overdrive.app.navmap.google

import android.util.Log
import com.overdrive.app.navmap.glass.MapGlassBasemap
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.util.WeakHashMap

/** MapLibre style pieces for the Google raster basemap and the traffic overlay. Style/map calls run on the main thread. */
object GoogleMapStyle {

    const val BASE_SOURCE_ID = "google-base"
    const val BASE_LAYER_ID = "google-base-layer"
    const val PROGRAM_RESET_LAYER_ID = "google-program-reset"
    const val TRAFFIC_SOURCE_ID = "google-traffic"
    const val TRAFFIC_LAYER_ID = "google-traffic-layer"
    const val GLYPHS_URL = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"
    const val TRAFFIC_REFRESH_MS = 180_000L
    const val TRAFFIC_REFRESH_PAID_MS = 60_000L

    private const val TAG = "GoogleMapStyle"
    private const val TILE_SIZE = 256
    private const val TRAFFIC_MAX_ZOOM = 21f

    private class LodDefaults(val minRadius: Double, val scale: Double, val pitchThreshold: Double, val zoomShift: Double)

    private val lodDefaults = WeakHashMap<MapLibreMap, LodDefaults>()

    @JvmStatic
    fun googleStyleJson(base: TileSession, maxZoom: Int, backgroundHex: String): String {
        val source = JSONObject()
            .put("type", "raster")
            .put("tiles", JSONArray().put(GoogleTileSessions.tileUrlTemplate(base)))
            .put("tileSize", TILE_SIZE)
            .put("maxzoom", maxZoom)
            .put("attribution", "© Google")
        val background = JSONObject()
            .put("id", "background")
            .put("type", "background")
            .put("paint", JSONObject().put("background-color", backgroundHex))
        val raster = JSONObject()
            .put("id", BASE_LAYER_ID)
            .put("type", "raster")
            .put("source", BASE_SOURCE_ID)
            .put("paint", JSONObject().put("raster-fade-duration", 100))
        // Draws every frame with a non-raster program: MapLibre 11.13's LocationIndicatorLayer unbinds the GL program,
        // and the rasters rebind theirs only after another program has been used.
        val programReset = JSONObject()
            .put("id", PROGRAM_RESET_LAYER_ID)
            .put("type", "background")
            .put("paint", JSONObject().put("background-color", "rgba(0,0,0,0)"))
        return JSONObject()
            .put("version", 8)
            .put("name", "google")
            .put("glyphs", GLYPHS_URL)
            .put("sources", JSONObject().put(BASE_SOURCE_ID, source))
            .put("layers", JSONArray().put(background).put(raster).put(programReset))
            .toString()
    }

    @JvmStatic
    fun addTrafficLayer(style: Style, traffic: TileSession, belowLayerId: String?) {
        if (!style.isFullyLoaded) return
        try {
            // Marked before the source exists so the first traffic tile is already sniffed.
            GoogleHttp.markTrafficSession(traffic.token)
            if (style.getSource(TRAFFIC_SOURCE_ID) == null) {
                val tiles = TileSet("2.1.0", GoogleTileSessions.tileUrlTemplate(traffic))
                tiles.setMaxZoom(TRAFFIC_MAX_ZOOM)
                style.addSource(RasterSource(TRAFFIC_SOURCE_ID, tiles, TILE_SIZE))
            }
            if (style.getLayer(TRAFFIC_LAYER_ID) == null) {
                val layer = RasterLayer(TRAFFIC_LAYER_ID, TRAFFIC_SOURCE_ID)
                    .withProperties(PropertyFactory.rasterFadeDuration(0f))
                if (belowLayerId != null && style.getLayer(belowLayerId) != null) {
                    style.addLayerBelow(layer, belowLayerId)
                } else {
                    style.addLayer(layer)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "addTrafficLayer failed: ${t.javaClass.simpleName}")
        }
    }

    @JvmStatic
    fun setTrafficVisible(style: Style, visible: Boolean) {
        if (!style.isFullyLoaded) return
        try {
            style.getLayer(TRAFFIC_LAYER_ID)?.setProperties(
                PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setTrafficVisible failed: ${t.javaClass.simpleName}")
        }
    }

    /** [basePrefetchDelta]: zoom levels below the view the basemap loads first, shown while the sharp tiles load. */
    @JvmStatic
    fun tuneSources(style: Style, trafficRefreshMs: Long, basePrefetchDelta: Int) {
        if (!style.isFullyLoaded) return
        try {
            style.getSource(BASE_SOURCE_ID)?.setPrefetchZoomDelta(basePrefetchDelta)
            style.getSource(TRAFFIC_SOURCE_ID)?.let { src ->
                src.setPrefetchZoomDelta(0)
                src.setVolatile(true)
                src.setMinimumTileUpdateInterval(trafficRefreshMs)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "tuneSources failed: ${t.javaClass.simpleName}")
        }
    }

    @JvmStatic
    fun applyLod(map: MapLibreMap, google: Boolean) {
        try {
            val d = synchronized(lodDefaults) {
                lodDefaults.getOrPut(map) {
                    LodDefaults(map.tileLodMinRadius, map.tileLodScale, map.tileLodPitchThreshold, map.tileLodZoomShift)
                }
            }
            if (google) {
                // Fewer distant tiles under the pitched nav camera: each raster tile is billable.
                map.tileLodPitchThreshold = Math.toRadians(30.0)
                map.tileLodScale = 1.5
            } else {
                // Restore only what differs, so a vector-only map sees no setter calls.
                if (map.tileLodMinRadius != d.minRadius) map.tileLodMinRadius = d.minRadius
                if (map.tileLodScale != d.scale) map.tileLodScale = d.scale
                if (map.tileLodPitchThreshold != d.pitchThreshold) map.tileLodPitchThreshold = d.pitchThreshold
                if (map.tileLodZoomShift != d.zoomShift) map.tileLodZoomShift = d.zoomShift
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyLod failed: ${t.javaClass.simpleName}")
        }
    }

    /** Map Tiles `styles` for the night roadmap, in the vector night basemap's colours so both maps look the same. */
    @JvmStatic
    fun nightStylesJson(): String = nightStyles

    private val nightStyles: String by lazy {
        val rules = JSONArray()
        fun rule(featureType: String?, elementType: String, color: String) {
            val o = JSONObject()
            // No featureType selects every feature ("all").
            if (featureType != null) o.put("featureType", featureType)
            o.put("elementType", elementType)
            o.put("stylers", JSONArray().put(JSONObject().put("color", color)))
            rules.put(o)
        }
        rule(null, "geometry", MapGlassBasemap.N_LAND)
        rule("landscape.man_made", "geometry.fill", MapGlassBasemap.N_BLD)
        rule("landscape.man_made", "geometry.stroke", MapGlassBasemap.N_BLD_LINE)
        rule("landscape.natural", "geometry", MapGlassBasemap.N_PARK)
        rule("poi.park", "geometry", MapGlassBasemap.N_PARK)
        rule("water", "geometry", MapGlassBasemap.N_WATER)
        rule("road", "geometry.fill", MapGlassBasemap.N_MINOR)
        rule("road", "geometry.stroke", MapGlassBasemap.N_CASING)
        rule("road.arterial", "geometry.fill", MapGlassBasemap.N_SEC)
        rule("road.highway", "geometry.fill", MapGlassBasemap.N_PRIM)
        rule("road.highway.controlled_access", "geometry.fill", MapGlassBasemap.N_MWAY)
        rule("transit", "geometry", MapGlassBasemap.N_RAIL)
        rule("administrative", "geometry.stroke", MapGlassBasemap.N_BLD_LINE)
        rule("administrative.country", "geometry.stroke", MapGlassBasemap.N_BOUNDARY)
        rule("administrative.province", "geometry.stroke", MapGlassBasemap.N_BOUNDARY)
        rule(null, "labels.text.fill", MapGlassBasemap.N_LABEL)
        rule(null, "labels.text.stroke", MapGlassBasemap.N_HALO)
        rule("road.highway", "labels.text.fill", MapGlassBasemap.N_LABEL_MAJOR)
        rule("administrative.locality", "labels.text.fill", MapGlassBasemap.N_PLACE)
        rule("water", "labels.text.fill", MapGlassBasemap.N_WATER_LABEL)
        rule("transit", "labels.text.fill", MapGlassBasemap.N_TRANSIT)
        rules.toString()
    }
}
