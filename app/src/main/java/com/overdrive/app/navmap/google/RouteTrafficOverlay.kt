package com.overdrive.app.navmap.google

import android.util.Log
import com.overdrive.app.navmap.glass.CoordBuf
import com.overdrive.app.navmap.glass.GlassLines
import com.overdrive.app.navmap.glass.MapGlassPalette
import com.overdrive.app.navmap.glass.RibbonTrack
import com.overdrive.app.navmap.glass.SpanSet
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Congested stretches of the route, one LineString per span clipped to [cut, end] with `level` slow|jam.
 * Driven by MapGlassLayers through install(), setData() and setRange(). Main thread only; never throws.
 */
class RouteTrafficOverlay {

    companion object {
        const val GLASS_SOURCE_ID = "od-traffic"
        const val PROP_LEVEL = "level"

        /** Glass layers in z-order; lowTier drops the two spec lines. */
        fun glassLayerIds(lowTier: Boolean): List<String> {
            val out = arrayListOf("od-traffic-slow-glow", "od-traffic-jam-glow")
            for (lv in LEVELS) {
                out.add("od-traffic-$lv-rim"); out.add("od-traffic-$lv-body"); out.add("od-traffic-$lv-core")
                if (!lowTier) out.add("od-traffic-$lv-spec")
            }
            return out
        }

        private const val TAG = "RouteTrafficOverlay"
        private val LEVELS = arrayOf("slow", "jam")
        private const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        private const val MIN_PIECE_M = 1.0
        private const val SLOW_FADE_IN = 0.09
        private const val JAM_FADE_OUT = 0.9
    }

    private var style: Style? = null
    private var source: GeoJsonSource? = null

    private var track: RibbonTrack? = null
    private var spans: SpanSet? = null
    private var cutM = Double.NaN
    private var endM = Double.NaN
    private var lastJson: String? = null

    private val buf = CoordBuf()
    private val sb = StringBuilder(4096)

    // ── Glass ───────────────────────────────────────────────────────────────

    /**
     * Adds `od-traffic` and its layers right above [aboveLayerId] (else below [belowLayerId], else on top) and
     * redraws. Returns the id of the topmost layer added, for chaining.
     */
    fun install(style: Style, night: Boolean, lowTier: Boolean, aboveLayerId: String?, belowLayerId: String? = null): String? {
        dropRefs()
        if (!loaded(style)) return aboveLayerId
        return try {
            removeAll(style)
            val src = GeoJsonSource(GLASS_SOURCE_ID, EMPTY, GeoJsonOptions().withLineMetrics(true))
            style.addSource(src)
            var above = aboveLayerId
            for (l in glassLayers(MapGlassPalette.of(night), lowTier)) {
                add(style, l, above, belowLayerId)
                above = l.id
            }
            this.style = style
            source = src
            render()
            above
        } catch (t: Throwable) {
            Log.w(TAG, "install failed: ${t.javaClass.simpleName}")
            dropRefs()
            aboveLayerId
        }
    }

    /** Spans of [track] (null clears). */
    internal fun setData(track: RibbonTrack?, spans: SpanSet?) {
        if (track === this.track && spans === this.spans) return
        this.track = track
        this.spans = spans
        render()
    }

    /** Draws only [cutM, endM] of each span (NaN = unbounded). */
    internal fun setRange(cutM: Double, endM: Double) {
        if (same(cutM, this.cutM) && same(endM, this.endM)) return
        this.cutM = cutM
        this.endM = endM
        render()
    }

    fun detach() = dropRefs()

    // ── Drawing ─────────────────────────────────────────────────────────────

    private fun render() {
        val src = source ?: return
        val s = style
        if (s == null || !loaded(s)) { dropRefs(); return }
        val json = build()
        if (json == lastJson) return
        try {
            src.setGeoJson(json)
            lastJson = json
        } catch (t: Throwable) {
            Log.w(TAG, "setGeoJson failed: ${t.javaClass.simpleName}")
        }
    }

    private fun build(): String {
        val t = track ?: return EMPTY
        val ss = spans ?: return EMPTY
        sb.setLength(0)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (k in 0 until ss.size) {
            val u = if (cutM.isNaN()) ss.a[k] else max(ss.a[k], cutM)
            val v = if (endM.isNaN()) ss.b[k] else min(ss.b[k], endM)
            if (v - u < MIN_PIECE_M) continue
            t.slice(u, v, buf)
            if (buf.n < 2) continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"").append(PROP_LEVEL).append("\":\"")
                .append(if (ss.level[k] == SpanSet.JAM) "jam" else "slow")
                .append("\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            for (i in 0 until buf.n) {
                if (i > 0) sb.append(',')
                sb.append('[').append(buf.lng(i)).append(',').append(buf.lat(i)).append(']')
            }
            sb.append("]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun glassLayers(p: MapGlassPalette, lowTier: Boolean): List<LineLayer> {
        val out = ArrayList<LineLayer>(10)
        val body = GlassLines.BODY
        for (lv in LEVELS) {
            out.add(line("od-traffic-$lv-glow", lv, true).withProperties(
                PropertyFactory.lineGradient(fade(lv, ribbon(p, lv).glow)),
                PropertyFactory.lineOpacity(p.trafficGlowOpacity),
                PropertyFactory.lineWidth(GlassLines.wz(body, 3.0)),
                PropertyFactory.lineBlur(GlassLines.wz(body, 1.4))))
        }
        for (lv in LEVELS) {
            val r = ribbon(p, lv)
            out.add(line("od-traffic-$lv-rim", lv, false).withProperties(
                PropertyFactory.lineGradient(fade(lv, r.rim)),
                PropertyFactory.lineWidth(GlassLines.wz(body, 1.0, 5.0))))
            out.add(line("od-traffic-$lv-body", lv, false).withProperties(
                PropertyFactory.lineGradient(fade(lv, r.body)),
                PropertyFactory.lineWidth(GlassLines.wz(body))))
            out.add(line("od-traffic-$lv-core", lv, false).withProperties(
                PropertyFactory.lineGradient(fade(lv, r.core)),
                PropertyFactory.lineWidth(GlassLines.wz(body, 0.5)),
                PropertyFactory.lineBlur(GlassLines.wz(body, 0.3))))
            if (!lowTier) out.add(line("od-traffic-$lv-spec", lv, false).withProperties(
                PropertyFactory.lineGradient(fade(lv, p.specular)),
                PropertyFactory.lineOpacity(p.trafficSpecOpacity),
                PropertyFactory.lineWidth(GlassLines.wz(body, 0.15)),
                PropertyFactory.lineOffset(GlassLines.wz(body, -0.24)),
                PropertyFactory.lineBlur(0.6f)))
        }
        return out
    }

    private fun ribbon(p: MapGlassPalette, lv: String) = if (lv == "jam") p.jam else p.slow

    // Slow blends out of the route colour at its start; jam fades at its end; where they meet the join is hard.
    private fun fade(lv: String, c: Int): Expression =
        if (lv == "jam") GlassLines.fadeOut(c, JAM_FADE_OUT) else GlassLines.fadeIn(c, SLOW_FADE_IN)

    private fun line(id: String, lv: String, roundCap: Boolean) = LineLayer(id, GLASS_SOURCE_ID)
        .withFilter(Expression.eq(Expression.get(PROP_LEVEL), Expression.literal(lv)))
        .withProperties(
            PropertyFactory.lineCap(if (roundCap) Property.LINE_CAP_ROUND else Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))

    private fun add(style: Style, layer: Layer, above: String?, below: String?) {
        when {
            above != null && style.getLayer(above) != null -> style.addLayerAbove(layer, above)
            below != null && style.getLayer(below) != null -> style.addLayerBelow(layer, below)
            else -> style.addLayer(layer)
        }
    }

    private fun removeAll(style: Style) {
        for (id in glassLayerIds(false)) style.getLayer(id)?.let { style.removeLayer(it) }
        if (style.getSource(GLASS_SOURCE_ID) != null) style.removeSource(GLASS_SOURCE_ID)
    }

    private fun dropRefs() {
        style = null
        source = null
        lastJson = null
    }

    private fun loaded(s: Style): Boolean = try { s.isFullyLoaded } catch (_: Throwable) { false }

    private fun same(a: Double, b: Double): Boolean = if (a.isNaN() || b.isNaN()) a.isNaN() && b.isNaN() else abs(a - b) < 0.01
}
