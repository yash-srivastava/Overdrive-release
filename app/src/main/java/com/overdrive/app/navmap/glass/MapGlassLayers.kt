package com.overdrive.app.navmap.glass

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.overdrive.app.navmap.google.RouteTrafficOverlay
import com.overdrive.app.navmap.motion.RouteTrack
import com.overdrive.app.navmap.nav.CongestionLevel
import com.overdrive.app.navmap.nav.CongestionSpan
import com.overdrive.app.navmap.nav.GeoPoint
import com.overdrive.app.navmap.nav.NavRoute
import com.overdrive.app.navmap.nav.RouteManeuver
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Glass overlay of one map (SPEC §4): alternates, route ribbon, congestion, turn arrow, hazards, pins.
 * Main thread only. State outlives styles: install() on a new style redraws everything.
 */
class MapGlassLayers {

    companion object {
        private const val TAG = "MapGlassLayers"

        const val ROUTE_SOURCE_ID = "od-route"
        const val ROUTE_B_SOURCE_ID = "od-route-b"
        const val FAR_SOURCE_ID = "od-route-far"
        const val ALT_SOURCE_ID = "od-alt"
        const val ARROW_SOURCE_ID = "od-arrow"
        const val ARROW_HEAD_SOURCE_ID = "od-arrow-head"
        const val PIN_SOURCE_ID = "od-pins"

        /** Hit-test layer for alternates; features carry `idx` = index in the list given to setAlternates. */
        const val ALT_LAYER_ID = "od-alt-rim"
        const val HAZARD_CONTACT_LAYER_ID = "od-hazard-contact"
        /** Tap target for single hazards (features keep the source's `id`/`type`/`severity`/… properties). */
        const val HAZARD_GEM_LAYER_ID = "od-hazard-gem"
        const val HAZARD_CLUSTER_LAYER_ID = "od-hazard-cluster"
        @JvmField val HAZARD_LAYER_IDS = arrayOf(HAZARD_CONTACT_LAYER_ID, HAZARD_GEM_LAYER_ID, HAZARD_CLUSTER_LAYER_ID)
        const val PIN_CONTACT_LAYER_ID = "od-pin-contact"
        /** Topmost overlay layer; the puck is added above it. */
        const val TOP_LAYER_ID = "od-pin"

        // Bitmaps registered by MapGlassArt.
        const val IMG_HEAD = "od-head"
        const val IMG_HEAD_RIM = "od-head-rim"
        const val IMG_HEAD_SHADOW = "od-head-shadow"
        const val IMG_CONTACT = "od-contact"
        const val IMG_PIN_DEST = "od-pin-dest"
        const val IMG_VIA_PREFIX = "od-via-"
        const val IMG_CLUSTER_PREFIX = "od-cluster-"
        const val IMG_CLUSTER_MAX = "od-cluster-99+"
        const val IMG_GEM_PREFIX = "od-gem-"

        private const val PROP_TYPE = "type"
        private const val PROP_SEVERITY = "severity"
        private const val PROP_STATUS = "status"
        private const val POINT_COUNT = "point_count"

        private const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        // The written-but-hidden ribbon becomes visible after this; GeoJSON lands on a worker a few frames late.
        private const val SETTLE_MS = 600L
        private const val OFFSCREEN_DP = 100_000f
        private const val SHADOW_DROP_DP = 5f

        private const val ARROW_BACK_M = 35.0
        private const val ARROW_FWD_M = 32.0
        private const val HEAD_AT_M = 28.0
        private const val HEAD_BEARING_FROM_M = 27.0
        private const val HEAD_BEARING_SPAN_M = 4.0
        // An arrow behind the car is never useful: it drops once the car is this far past the turn.
        private const val ARROW_PASSED_M = 10.0

        private const val SHADOW = 0
        private const val RIM = 1
        private const val BODY = 2
        private const val CORE = 3
        private const val SPEC = 4

        // Marker scale by zoom: the art is sized for the follow camera (z17.4, 56° tilt); it holds ≥0.83 down to the
        // 130 km/h drive zoom (15.3) so hazards stay legible at speed, and only overview zooms shrink it.
        private val GEM_ZOOM = doubleArrayOf(11.0, 0.36, 13.0, 0.5, 14.5, 0.72, 15.5, 0.86, 16.5, 0.95, 17.4, 1.0)
        private val PIN_ZOOM = doubleArrayOf(11.0, 0.5, 14.0, 0.62, 16.0, 0.76, 17.4, 1.0)

        /** interpolate(zoom) over [stops] (zoom, factor pairs), each factor times [base] when given. */
        fun byZoom(stops: DoubleArray, base: Expression? = null): Expression {
            val s = Array(stops.size / 2) { i ->
                val f = Expression.literal(stops[2 * i + 1])
                Expression.stop(stops[2 * i], if (base == null) f else Expression.product(f, base))
            }
            return Expression.interpolate(Expression.linear(), Expression.zoom(), *s)
        }
    }

    /** One copy of the near-window ribbon; two alternate so new geometry lands hidden (line-translate off screen). */
    private class Ribbon(val sourceId: String, prefix: String) {
        val ids = arrayOf("$prefix-shadow", "$prefix-rim", "$prefix-body", "$prefix-core", "$prefix-spec")
        var source: GeoJsonSource? = null
        val layers = arrayOfNulls<LineLayer>(5)
        var track: RibbonTrack? = null
        var w0 = 0.0
        var w1 = 0.0
        var m0 = 0.0
        var m1 = 0.0
        val applied = Stops()
        var appliedValid = false

        fun forget() {
            track = null
            w0 = 0.0; w1 = 0.0; m0 = 0.0; m1 = 0.0
            appliedValid = false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val settleTask = Runnable { activatePending() }

    private var style: Style? = null
    private var hazardStyle: Style? = null
    private var night = false
    private var lowTier = false
    private var topBelowId: String? = null

    private val plan = RibbonPlan()
    private val ribbons = arrayOf(Ribbon(ROUTE_SOURCE_ID, "od-route"), Ribbon(ROUTE_B_SOURCE_ID, "od-route-b"))
    private var active = 0
    private var pending = -1
    private var farSource: GeoJsonSource? = null
    private var farWritten = -1
    private var altSource: GeoJsonSource? = null
    private var arrowSource: GeoJsonSource? = null
    private var arrowHeadSource: GeoJsonSource? = null
    private var pinSource: GeoJsonSource? = null
    private val traffic = RouteTrafficOverlay()

    private var alternates: List<NavRoute> = emptyList()
    private var altSelected = -1
    private var congestionRoute: NavRoute? = null
    private var congestionSpans: List<CongestionSpan> = emptyList()
    private var spans: SpanSet? = null
    private var arrowRoute: NavRoute? = null
    private var arrowTrack: RibbonTrack? = null
    private var arrowIndex = -1
    private var arrowType = -1
    private var arrowTurnM = Double.NaN
    private var arrowShown = false
    private var pinStops: List<GeoPoint> = emptyList()
    private var pinDest: GeoPoint? = null
    private var hazardSourceId: String? = null
    private var hazardMinSeverity = 1
    private var hazardVisible = true

    private val coords = CoordBuf()
    private val sb = StringBuilder(16 * 1024)
    private val stops = Stops()
    private val pieces = Pieces()
    private val pieceBuilder = PieceBuilder()
    private val gradients = GradientBuilder()
    private val knots = DoubleArray(2)
    private val head = DoubleArray(3)

    private val ribbonColor = Array(5) { IntArray(3) }
    private val solid = Array(5) { arrayOfNulls<Expression>(3) }
    private val clear = Array(5) { arrayOfNulls<Expression>(3) }
    private val zeroTransition = TransitionOptions(0, 0)

    // ── Install ─────────────────────────────────────────────────────────────

    /**
     * Adds every overlay source and layer to [style] (call once per style load, after MapGlassBasemap.apply and
     * the hazard source) and redraws the current state. Ribbon + congestion go right above the topmost
     * fill-extrusion layer (`building-3d`); without one (Google raster) they go below the arrow/hazard/pin group,
     * which sits at the top of the style or below [topBelowLayerId] when that layer exists.
     */
    @JvmOverloads
    fun install(style: Style, night: Boolean, lowTier: Boolean, topBelowLayerId: String? = null, palette: MapGlassPalette = MapGlassPalette.of(night)) {
        handler.removeCallbacks(settleTask)
        pending = -1
        dropRefs()
        if (!loaded(style)) return
        this.night = night
        this.lowTier = lowTier
        topBelowId = topBelowLayerId
        val pal = palette
        cacheColors(pal)
        try {
            removeOwn(style)
            addSources(style)
            addRibbonGroup(style, pal)
            addArrowGroup(style, pal)
            hazardSourceId?.let { if (style.getSource(it) != null) addHazardGroup(style, it) }
            addPinGroup(style)
            if (lowTier) opaqueExtrusions(style)
            this.style = style
        } catch (t: Throwable) {
            Log.w(TAG, "install failed: ${t.javaClass.simpleName}: ${t.message}")
            dropRefs()
            return
        }
        redrawAll()
    }

    /**
     * Layers for the activity's clustered hazard source (`type` 1 pothole, 0 breaker, 3 rough, else unknown;
     * `severity` 1–3; `status` 0 = candidate, drawn dimmer). Placed under the pins; safe before or after install().
     */
    fun installHazards(style: Style, sourceId: String) {
        hazardSourceId = sourceId
        if (!loaded(style) || style.getSource(sourceId) == null) return
        try {
            for (id in HAZARD_LAYER_IDS) style.getLayer(id)?.let { style.removeLayer(it) }
            addHazardGroup(style, sourceId)
        } catch (t: Throwable) {
            Log.w(TAG, "installHazards failed: ${t.javaClass.simpleName}")
        }
    }

    /** Hazard filter: single hazards below [minSeverity] are hidden; clusters stay; [visible] false hides all. */
    fun setHazardFilter(minSeverity: Int, visible: Boolean) {
        hazardMinSeverity = minSeverity.coerceIn(1, 3)
        hazardVisible = visible
        val s = hazardStyle ?: return
        if (!loaded(s)) { hazardStyle = null; return }
        applyHazardFilter(s)
    }

    /** Drops style references (onDestroy). State is kept; install() redraws it. */
    fun detach() {
        handler.removeCallbacks(settleTask)
        pending = -1
        dropRefs()
        traffic.detach()
    }

    // ── Route ───────────────────────────────────────────────────────────────

    /**
     * [navigating] false: the whole route (preview / overview; trimmed if progress is known for this route).
     * true: windowed ribbon car − 60 m … car + 2.5 km plus a plain far ribbon. A different route resets progress.
     */
    fun setRoute(route: NavRoute?, navigating: Boolean) {
        val current = plan.track?.route
        val usable = route != null && route.points.size >= 2
        if (!usable) {
            if (current == null) { plan.reset(null, navigating); return }
            plan.reset(null, navigating)
            spans = null
            if (arrowRoute != null) clearArrow()
            clearRibbons()
            pushTraffic()
            return
        }
        if (route === current && navigating == plan.windowed) return
        val wasTrimmed = !plan.progress.isNaN()
        if (route === current) {
            plan.setWindowed(navigating)
        } else {
            plan.reset(RibbonTrack.of(route!!), navigating)
            if (arrowRoute != null && arrowRoute !== route) clearArrow()
        }
        spans = plan.track?.let { t -> if (congestionRoute === route) SpanSet.of(t, congestionSpans) else null }
        refreshRibbon(settle = wasTrimmed)
    }

    /** Forgets the car position on the current route (guidance restart): full route, no trim. */
    fun resetProgress() {
        if (plan.progress.isNaN()) return
        plan.clearProgress()
        refreshRibbon(settle = true)
        if (arrowRoute != null && !arrowShown) { arrowShown = true; writeArrow() }
    }

    /**
     * Car position along the current route (metres, haversine sums like the guidance engine), every frame.
     * NaN or a value behind the last one holds the trim (off route / jitter). Allocates only when it redraws.
     */
    fun setProgress(alongM: Double) {
        if (!plan.advance(alongM)) return
        val s = plan.progress
        if (arrowShown && s >= arrowTurnM + ARROW_PASSED_M && arrowTrack === plan.track) hideArrow()
        if (style == null) return
        val activeEnd = ribbons[active].w1
        if (pending < 0 && plan.sliceWanted(activeEnd)) {
            val now = SystemClock.elapsedRealtime()
            if (plan.sliceAllowed(now, activeEnd)) {
                slice(now)
                return
            }
        }
        if (plan.trimDue()) applyTrim(active, false)
    }

    /** Preview alternates: every route except [selected], as a quiet neutral ribbon under the route. */
    fun setAlternates(routes: List<NavRoute>, selected: Int) {
        if (selected == altSelected && sameRoutes(routes, alternates)) return
        alternates = ArrayList(routes)
        altSelected = selected
        writeAlternates()
    }

    /** Congestion of [route] (spans on its point indices); drawn when [route] is the route shown. */
    fun setCongestion(route: NavRoute?, spans: List<CongestionSpan>) {
        congestionRoute = route
        congestionSpans = ArrayList(spans)
        val t = plan.track
        this.spans = if (t != null && route === t.route) SpanSet.of(t, congestionSpans) else null
        pushTraffic()
        writeFar(force = true)
        applyTrim(active, false)
    }

    /** On-road arrow for [maneuver] of [route]; null hides. Repeated calls for the same maneuver are free. */
    fun setManeuverArrow(route: NavRoute?, maneuver: RouteManeuver?) {
        if (route == null || maneuver == null || route.points.size < 2) {
            if (arrowRoute != null) clearArrow()
            return
        }
        if (route === arrowRoute && maneuver.beginShapeIndex == arrowIndex && maneuver.type == arrowType) return
        val t = plan.track?.takeIf { it.route === route } ?: RibbonTrack.of(route)
        arrowRoute = route
        arrowTrack = t
        arrowIndex = maneuver.beginShapeIndex
        arrowType = maneuver.type
        arrowTurnM = t.cum[arrowIndex.coerceIn(0, t.size - 1)]
        val p = if (t === plan.track) plan.progress else Double.NaN
        arrowShown = p.isNaN() || p < arrowTurnM + ARROW_PASSED_M
        writeArrow()
    }

    /** Via stops (numbered 1…n in order) and the destination. */
    fun setPins(stops: List<GeoPoint>, dest: GeoPoint?) {
        if (dest == pinDest && stops == pinStops) return
        pinStops = ArrayList(stops)
        pinDest = dest
        writePins()
    }

    /** Clears route, alternates, congestion, arrow and pins (trip ended). */
    fun clear() {
        setRoute(null, false)
        setAlternates(emptyList(), -1)
        setCongestion(null, emptyList())
        setManeuverArrow(null, null)
        setPins(emptyList(), null)
    }

    // ── Ribbon pipeline ─────────────────────────────────────────────────────

    // Settle: the shown ribbon stays until the new one is laid out (route change or mode change under a trim).
    private fun refreshRibbon(settle: Boolean) {
        handler.removeCallbacks(settleTask)
        pending = -1
        plan.planWindow(SystemClock.elapsedRealtime())
        if (live()) {
            if (settle && ribbons[active].track != null && plan.track != null) {
                writeRibbon(1 - active)
                schedule(1 - active)
            } else {
                writeRibbon(active)
                writeFar(force = false)
                applyTrim(active, true)
            }
        }
        pushTraffic()
    }

    private fun slice(now: Long) {
        plan.planWindow(now)
        if (plan.windowed && ribbons[active].track != null) {
            writeRibbon(1 - active)
            schedule(1 - active)
        }
        writeFar(force = false)
        pushTraffic()
        applyTrim(active, false)
    }

    private fun schedule(target: Int) {
        pending = target
        handler.removeCallbacks(settleTask)
        handler.postDelayed(settleTask, SETTLE_MS)
    }

    private fun activatePending() {
        val i = pending
        if (i < 0) return
        pending = -1
        if (!live()) return
        showRibbon(i)
        active = i
        writeFar(force = false)
        applyTrim(i, true)
    }

    private fun writeRibbon(i: Int) {
        val r = ribbons[i]
        val t = plan.track
        if (t == null) {
            r.forget()
            setJson(r.source, EMPTY)
            return
        }
        r.track = t
        r.w0 = plan.plannedW0
        r.w1 = plan.plannedW1
        r.m0 = t.mercAt(r.w0)
        r.m1 = t.mercAt(r.w1)
        r.appliedValid = false
        t.slice(r.w0, r.w1, coords)
        setJson(r.source, lineJson(coords))
    }

    private fun clearRibbons() {
        handler.removeCallbacks(settleTask)
        pending = -1
        for (r in ribbons) {
            r.forget()
            setJson(r.source, EMPTY)
        }
        writeFar(force = true)
    }

    /** Trim + baked congestion gradient on ribbon [i]; skipped when the stops did not change. */
    private fun applyTrim(i: Int, force: Boolean) {
        val r = ribbons[i]
        val t = r.track ?: return
        if (t !== plan.track) return
        if (i == active) plan.trimCar = plan.progress
        if (r.m1 - r.m0 <= 0.0) return
        plan.trimKnots(t, r.m0, r.m1, knots)
        pieces.clear()
        val ss = spans
        if (ss != null && !plan.cut.isNaN()) {
            pieceBuilder.build(ss, r.w0, r.w1, plan.cut, pieces)
            val span = r.m1 - r.m0
            for (k in 0 until pieces.n) {
                pieces.u[k] = (t.mercAt(pieces.u[k]) - r.m0) / span
                pieces.v[k] = (t.mercAt(pieces.v[k]) - r.m0) / span
            }
        }
        gradients.trimStops(knots[0], knots[1], pieces, stops, GradientBuilder.RAMP_TEXEL)
        if (!force && r.appliedValid && r.applied.sameAs(stops)) return
        r.applied.copyFrom(stops)
        r.appliedValid = true
        if (!live()) return
        try {
            for (k in 0 until 5) r.layers[k]?.setProperties(PropertyFactory.lineGradient(gradient(k, stops)))
        } catch (e: Throwable) {
            Log.w(TAG, "gradient failed: ${e.javaClass.simpleName}")
        }
    }

    private fun showRibbon(i: Int) {
        try {
            for (j in 0..1) {
                val on = j == i
                val layers = ribbons[j].layers
                for (k in 0 until 5) {
                    val l = layers[k] ?: continue
                    val dy = if (k == SHADOW) SHADOW_DROP_DP else 0f
                    l.setProperties(PropertyFactory.lineTranslate(arrayOf(if (on) 0f else OFFSCREEN_DP, dy)))
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ribbon switch failed: ${e.javaClass.simpleName}")
        }
    }

    private fun pushTraffic() {
        val t = plan.track
        traffic.setData(t, if (t != null && congestionRoute === t.route) spans else null)
        traffic.setRange(plan.cut, plan.ribbonEnd)
    }

    /** Far ribbon (plain rim + body, coloured by congestion level) from plan.farStart to plan.farEnd. */
    private fun writeFar(force: Boolean) {
        val src = farSource ?: return
        if (!force && farWritten == plan.farVersion) return
        farWritten = plan.farVersion
        val t = plan.track
        val start = plan.farStart
        if (t == null || start.isNaN()) { setJson(src, EMPTY); return }
        val end = plan.farEnd
        pieces.clear()
        val ss = if (congestionRoute === t.route) spans else null
        if (ss != null) pieceBuilder.build(ss, start, end, Double.POSITIVE_INFINITY, pieces)
        sb.setLength(0)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        var x = start
        var k = 0
        while (x < end - 0.5) {
            val nextLevelAt = if (k < pieces.n) pieces.u[k] else end
            val y: Double
            val lv: Int
            if (nextLevelAt > x + 0.5) { y = min(nextLevelAt, end); lv = SpanSet.NONE } else { y = pieces.v[k]; lv = pieces.level[k]; k++ }
            t.slice(x, y, coords)
            if (coords.n >= 2) {
                if (!first) sb.append(',')
                first = false
                appendLine(coords, levelProp(lv))
            }
            x = max(y, x + 0.5)
        }
        sb.append("]}")
        setJson(src, sb.toString())
    }

    // ── Other overlays ──────────────────────────────────────────────────────

    private fun writeAlternates() {
        val src = altSource ?: return
        sb.setLength(0)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for ((idx, r) in alternates.withIndex()) {
            if (idx == altSelected || r.points.size < 2) continue
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"idx\":").append(idx)
                .append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            for ((i, p) in r.points.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append('[').append(p.lng).append(',').append(p.lat).append(']')
            }
            sb.append("]}}")
        }
        sb.append("]}")
        setJson(src, sb.toString())
    }

    private fun writeArrow() {
        val t = arrowTrack
        if (!arrowShown || t == null || t.size < 2) {
            setJson(arrowSource, EMPTY)
            setJson(arrowHeadSource, EMPTY)
            return
        }
        t.arrow(arrowTurnM, ARROW_BACK_M, ARROW_FWD_M, HEAD_AT_M, HEAD_BEARING_FROM_M, HEAD_BEARING_SPAN_M, coords, head)
        setJson(arrowSource, lineJson(coords))
        sb.setLength(0)
        sb.append("{\"type\":\"Feature\",\"properties\":{\"bearing\":").append(head[2])
            .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(head[1]).append(',').append(head[0]).append("]}}")
        setJson(arrowHeadSource, sb.toString())
    }

    private fun hideArrow() {
        arrowShown = false
        writeArrow()
    }

    private fun clearArrow() {
        arrowRoute = null
        arrowTrack = null
        arrowIndex = -1
        arrowType = -1
        arrowTurnM = Double.NaN
        arrowShown = false
        writeArrow()
    }

    private fun writePins() {
        val src = pinSource ?: return
        sb.setLength(0)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for ((i, p) in pinStops.withIndex()) {
            if (!first) sb.append(',')
            first = false
            appendPoint(p, "\"kind\":\"via\",\"n\":${i + 1}")
        }
        pinDest?.let {
            if (!first) sb.append(',')
            appendPoint(it, "\"kind\":\"dest\"")
        }
        sb.append("]}")
        setJson(src, sb.toString())
    }

    private fun redrawAll() {
        active = 0
        farWritten = -1
        plan.planWindow(SystemClock.elapsedRealtime())
        writeRibbon(0)
        ribbons[1].forget()
        setJson(ribbons[1].source, EMPTY)
        showRibbon(0)
        writeFar(force = true)
        pushTraffic()
        applyTrim(0, true)
        writeAlternates()
        writeArrow()
        writePins()
        hazardStyle?.let { if (loaded(it)) applyHazardFilter(it) }
    }

    // ── Layers ──────────────────────────────────────────────────────────────

    private fun addSources(style: Style) {
        for (r in ribbons) {
            val s = GeoJsonSource(r.sourceId, EMPTY, GeoJsonOptions().withLineMetrics(true))
            style.addSource(s)
            r.source = s
        }
        farSource = GeoJsonSource(FAR_SOURCE_ID, EMPTY).also { style.addSource(it) }
        altSource = GeoJsonSource(ALT_SOURCE_ID, EMPTY).also { style.addSource(it) }
        arrowSource = GeoJsonSource(ARROW_SOURCE_ID, EMPTY, GeoJsonOptions().withLineMetrics(true)).also { style.addSource(it) }
        arrowHeadSource = GeoJsonSource(ARROW_HEAD_SOURCE_ID, EMPTY).also { style.addSource(it) }
        pinSource = GeoJsonSource(PIN_SOURCE_ID, EMPTY).also { style.addSource(it) }
    }

    private fun addRibbonGroup(style: Style, pal: MapGlassPalette) {
        val extrusion = style.layers.lastOrNull { it is FillExtrusionLayer }?.id
        val below = if (extrusion == null) firstExisting(style, "od-arrow-shadow", HAZARD_CONTACT_LAYER_ID, PIN_CONTACT_LAYER_ID, topBelowId) else null
        val chain = Chain(style, extrusion, below)

        val alt = pal.alternate
        chain.add(line(ALT_LAYER_ID, ALT_SOURCE_ID, true).withProperties(
            PropertyFactory.lineColor(alt.rim), PropertyFactory.lineWidth(GlassLines.wz(GlassLines.BODY, 0.8, 4.0))))
        chain.add(line("od-alt-body", ALT_SOURCE_ID, true).withProperties(
            PropertyFactory.lineColor(alt.body), PropertyFactory.lineWidth(GlassLines.wz(GlassLines.BODY, 0.8))))
        if (!lowTier) chain.add(line("od-alt-core", ALT_SOURCE_ID, true).withProperties(
            PropertyFactory.lineColor(alt.core), PropertyFactory.lineWidth(GlassLines.wz(GlassLines.BODY, 0.4)),
            PropertyFactory.lineBlur(GlassLines.wz(GlassLines.BODY, 0.24))))

        val lv = Expression.get(RouteTrafficOverlay.PROP_LEVEL)
        fun levelColor(route: Int, slow: Int, jam: Int) = Expression.match(lv, Expression.color(route),
            Expression.stop("slow", Expression.color(slow)), Expression.stop("jam", Expression.color(jam)))
        chain.add(line("od-route-far-rim", FAR_SOURCE_ID, false).withProperties(
            PropertyFactory.lineColor(levelColor(pal.route.rim, pal.slow.rim, pal.jam.rim)),
            PropertyFactory.lineWidth(GlassLines.wz(GlassLines.BODY, 1.0, 5.0))))
        chain.add(line("od-route-far-body", FAR_SOURCE_ID, false).withProperties(
            PropertyFactory.lineColor(levelColor(pal.route.body, pal.slow.body, pal.jam.body)),
            PropertyFactory.lineWidth(GlassLines.wz(GlassLines.BODY))))

        for ((n, r) in ribbons.withIndex()) {
            val off = arrayOf(if (n == 0) 0f else OFFSCREEN_DP, 0f)
            val offShadow = arrayOf(off[0], SHADOW_DROP_DP)
            val start = solid[SHADOW][0]!!
            r.layers[SHADOW] = ribbonLine(r.ids[SHADOW], r.sourceId, start, offShadow).withProperties(
                PropertyFactory.lineOpacity(pal.routeShadowOpacity),
                PropertyFactory.lineWidth(GlassLines.wz(GlassLines.BODY, 1.0, 9.0)),
                PropertyFactory.lineBlur(GlassLines.wz(GlassLines.BODY, 0.5, 2.0)))
            r.layers[RIM] = ribbonLine(r.ids[RIM], r.sourceId, solid[RIM][0]!!, off).withProperties(
                PropertyFactory.lineWidth(GlassLines.wz(GlassLines.BODY, 1.0, 5.0)))
            r.layers[BODY] = ribbonLine(r.ids[BODY], r.sourceId, solid[BODY][0]!!, off).withProperties(
                PropertyFactory.lineWidth(GlassLines.wz(GlassLines.BODY)))
            r.layers[CORE] = if (lowTier) null else ribbonLine(r.ids[CORE], r.sourceId, solid[CORE][0]!!, off).withProperties(
                PropertyFactory.lineWidth(GlassLines.wz(GlassLines.BODY, 0.5)),
                PropertyFactory.lineBlur(GlassLines.wz(GlassLines.BODY, 0.3)))
            r.layers[SPEC] = ribbonLine(r.ids[SPEC], r.sourceId, solid[SPEC][0]!!, off).withProperties(
                PropertyFactory.lineOpacity(pal.specOpacity),
                PropertyFactory.lineWidth(GlassLines.wz(GlassLines.BODY, 0.15)),
                PropertyFactory.lineOffset(GlassLines.wz(GlassLines.BODY, -0.24)),
                PropertyFactory.lineBlur(0.6f))
            for (l in r.layers) if (l != null) chain.add(l)
        }

        traffic.install(style, night, lowTier, chain.last, below)
    }

    private fun addArrowGroup(style: Style, pal: MapGlassPalette) {
        val chain = Chain(style, null, firstExisting(style, HAZARD_CONTACT_LAYER_ID, PIN_CONTACT_LAYER_ID, topBelowId))
        val viewport = Property.LINE_TRANSLATE_ANCHOR_VIEWPORT
        chain.add(arrowLine("od-arrow-shadow", pal.shadow).withProperties(
            PropertyFactory.lineOpacity(pal.arrowShadowOpacity),
            PropertyFactory.lineWidth(GlassLines.w2(GlassLines.SHAFT, 1.6)),
            PropertyFactory.lineBlur(GlassLines.w2(GlassLines.SHAFT, 0.5)),
            PropertyFactory.lineTranslate(arrayOf(0f, 7f)), PropertyFactory.lineTranslateAnchor(viewport)))
        chain.add(arrowHead("od-arrow-head-shadow", IMG_HEAD_SHADOW).withProperties(
            PropertyFactory.iconOpacity(pal.arrowShadowOpacity),
            PropertyFactory.iconTranslate(arrayOf(0f, 7f)),
            PropertyFactory.iconTranslateAnchor(Property.ICON_TRANSLATE_ANCHOR_VIEWPORT)))
        chain.add(arrowLine("od-arrow-side", pal.arrowRim).withProperties(
            PropertyFactory.lineWidth(GlassLines.w2(GlassLines.SHAFT, 1.2)),
            PropertyFactory.lineTranslate(arrayOf(0f, 3.5f)), PropertyFactory.lineTranslateAnchor(viewport)))
        chain.add(arrowHead("od-arrow-head-side", IMG_HEAD_RIM).withProperties(
            PropertyFactory.iconTranslate(arrayOf(0f, 3.5f)),
            PropertyFactory.iconTranslateAnchor(Property.ICON_TRANSLATE_ANCHOR_VIEWPORT)))
        chain.add(arrowLine("od-arrow-rim", pal.arrowRim).withProperties(
            PropertyFactory.lineWidth(GlassLines.w2(GlassLines.SHAFT, 1.2))))
        chain.add(arrowHead("od-arrow-head-rim", IMG_HEAD_RIM))
        chain.add(arrowLine("od-arrow-body", pal.arrowBody).withProperties(
            PropertyFactory.lineWidth(GlassLines.w2(GlassLines.SHAFT))))
        chain.add(arrowHead("od-arrow-head", IMG_HEAD))
    }

    private fun addHazardGroup(style: Style, sourceId: String) {
        val chain = Chain(style, null, firstExisting(style, PIN_CONTACT_LAYER_ID, topBelowId))
        val clustered = Expression.has(POINT_COUNT)
        val sev = hazardSeverity()
        val candidateDim = Expression.match(Expression.toNumber(Expression.get(PROP_STATUS)), Expression.literal(1.0),
            Expression.stop(0, 0.6))
        chain.add(SymbolLayer(HAZARD_CONTACT_LAYER_ID, sourceId).withProperties(
            PropertyFactory.iconImage(IMG_CONTACT),
            PropertyFactory.iconSize(byZoom(GEM_ZOOM, Expression.switchCase(clustered, Expression.literal(1.15),
                Expression.match(sev, Expression.literal(0.95), Expression.stop(1, 0.8), Expression.stop(3, 1.14))))),
            PropertyFactory.iconOpacity(Expression.switchCase(clustered, Expression.literal(1.0), candidateDim)),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        val kind = Expression.match(Expression.toNumber(Expression.coalesce(Expression.get(PROP_TYPE), Expression.literal(2))),
            Expression.literal("unknown"),
            Expression.stop(1, "pothole"), Expression.stop(0, "breaker"), Expression.stop(3, "rough"))
        chain.add(SymbolLayer(HAZARD_GEM_LAYER_ID, sourceId).withProperties(
            PropertyFactory.iconImage(Expression.concat(Expression.literal(IMG_GEM_PREFIX), kind, Expression.literal("-"),
                Expression.toString(sev))),
            PropertyFactory.iconOpacity(candidateDim),
            PropertyFactory.iconSize(byZoom(GEM_ZOOM)),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconOffset(arrayOf(0f, 20f)),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.symbolZOrder(Property.SYMBOL_Z_ORDER_VIEWPORT_Y),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        val count = Expression.toNumber(Expression.get(POINT_COUNT))
        chain.add(SymbolLayer(HAZARD_CLUSTER_LAYER_ID, sourceId).withProperties(
            PropertyFactory.iconImage(Expression.switchCase(Expression.gt(count, Expression.literal(99)), Expression.literal(IMG_CLUSTER_MAX),
                Expression.concat(Expression.literal(IMG_CLUSTER_PREFIX), Expression.toString(count)))),
            PropertyFactory.iconSize(byZoom(GEM_ZOOM)),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconOffset(arrayOf(0f, 19f)),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        hazardStyle = style
        applyHazardFilter(style)
    }

    private fun addPinGroup(style: Style) {
        val chain = Chain(style, null, firstExisting(style, topBelowId))
        val kind = Expression.get("kind")
        chain.add(SymbolLayer(PIN_CONTACT_LAYER_ID, PIN_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(IMG_CONTACT),
            PropertyFactory.iconSize(byZoom(PIN_ZOOM, Expression.match(kind, Expression.literal(0.95), Expression.stop("dest", 1.1)))),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        chain.add(SymbolLayer(TOP_LAYER_ID, PIN_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(Expression.match(kind,
                Expression.concat(Expression.literal(IMG_VIA_PREFIX), Expression.toString(Expression.get("n"))),
                Expression.stop("dest", IMG_PIN_DEST))),
            PropertyFactory.iconSize(byZoom(PIN_ZOOM)),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconOffset(arrayOf(0f, 20f)),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
    }

    private fun applyHazardFilter(style: Style) {
        try {
            val vis = PropertyFactory.visibility(if (hazardVisible) Property.VISIBLE else Property.NONE)
            val enough = Expression.gte(hazardSeverity(), Expression.literal(hazardMinSeverity))
            val clustered = Expression.has(POINT_COUNT)
            (style.getLayer(HAZARD_GEM_LAYER_ID) as? SymbolLayer)?.let {
                it.setFilter(Expression.all(Expression.not(clustered), enough)); it.setProperties(vis)
            }
            (style.getLayer(HAZARD_CONTACT_LAYER_ID) as? SymbolLayer)?.let {
                it.setFilter(Expression.any(clustered, enough)); it.setProperties(vis)
            }
            (style.getLayer(HAZARD_CLUSTER_LAYER_ID) as? SymbolLayer)?.let {
                it.setFilter(clustered); it.setProperties(vis)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hazard filter failed: ${t.javaClass.simpleName}")
        }
    }

    private fun hazardSeverity(): Expression = Expression.max(Expression.literal(1), Expression.min(Expression.literal(3),
        Expression.round(Expression.toNumber(Expression.coalesce(Expression.get(PROP_SEVERITY), Expression.literal(2))))))

    private fun line(id: String, source: String, roundCap: Boolean) = LineLayer(id, source).withProperties(
        PropertyFactory.lineCap(if (roundCap) Property.LINE_CAP_ROUND else Property.LINE_CAP_BUTT),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))

    private fun ribbonLine(id: String, source: String, start: Expression, translate: Array<Float>): LineLayer {
        val l = line(id, source, true).withProperties(
            PropertyFactory.lineGradient(Expression.interpolate(Expression.linear(), Expression.lineProgress(),
                Expression.stop(0.0, start), Expression.stop(1.0, start))),
            PropertyFactory.lineTranslate(translate),
            PropertyFactory.lineTranslateAnchor(Property.LINE_TRANSLATE_ANCHOR_VIEWPORT))
        l.lineTranslateTransition = zeroTransition
        return l
    }

    private fun arrowLine(id: String, color: Int) = line(id, ARROW_SOURCE_ID, false).withProperties(
        PropertyFactory.lineGradient(GlassLines.fadeIn(color, 0.32)))

    private fun arrowHead(id: String, image: String) = SymbolLayer(id, ARROW_HEAD_SOURCE_ID).withProperties(
        PropertyFactory.iconImage(image),
        PropertyFactory.iconRotate(Expression.get("bearing")),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
        PropertyFactory.iconSize(GlassLines.w2(GlassLines.HEAD)),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
        PropertyFactory.iconOffset(arrayOf(0f, -28f)),
        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true))

    private fun opaqueExtrusions(style: Style) {
        for (l in style.layers) if (l is FillExtrusionLayer) l.setProperties(PropertyFactory.fillExtrusionOpacity(1f))
    }

    private fun removeOwn(style: Style) {
        val ids = ArrayList<String>()
        ids.add(ALT_LAYER_ID); ids.add("od-alt-body"); ids.add("od-alt-core")
        ids.add("od-route-far-rim"); ids.add("od-route-far-body")
        for (r in ribbons) ids.addAll(r.ids)
        ids.addAll(listOf("od-arrow-shadow", "od-arrow-head-shadow", "od-arrow-side", "od-arrow-head-side",
            "od-arrow-rim", "od-arrow-head-rim", "od-arrow-body", "od-arrow-head"))
        ids.addAll(HAZARD_LAYER_IDS)
        ids.add(PIN_CONTACT_LAYER_ID); ids.add(TOP_LAYER_ID)
        for (id in ids) style.getLayer(id)?.let { style.removeLayer(it) }
        for (id in arrayOf(ROUTE_SOURCE_ID, ROUTE_B_SOURCE_ID, FAR_SOURCE_ID, ALT_SOURCE_ID, ARROW_SOURCE_ID,
            ARROW_HEAD_SOURCE_ID, PIN_SOURCE_ID)) {
            if (style.getSource(id) != null) style.removeSource(id)
        }
    }

    private fun cacheColors(pal: MapGlassPalette) {
        val rib = arrayOf(pal.route, pal.slow, pal.jam)
        for (slot in 0..2) {
            ribbonColor[SHADOW][slot] = pal.shadow
            ribbonColor[RIM][slot] = rib[slot].rim
            ribbonColor[BODY][slot] = rib[slot].body
            ribbonColor[CORE][slot] = rib[slot].core
            ribbonColor[SPEC][slot] = pal.specular
        }
        for (k in 0 until 5) for (slot in 0..2) {
            solid[k][slot] = GlassLines.rgba(ribbonColor[k][slot], 1.0)
            clear[k][slot] = GlassLines.rgba(ribbonColor[k][slot], 0.0)
        }
    }

    private fun gradient(kind: Int, st: Stops): Expression {
        val out = Array(st.n) { i ->
            val slot = st.slot[i].coerceIn(0, 2)
            val a = st.a[i]
            val c = when {
                a >= 1.0 -> solid[kind][slot]!!
                a <= 0.0 -> clear[kind][slot]!!
                else -> GlassLines.rgba(ribbonColor[kind][slot], a)
            }
            Expression.stop(st.t[i], c)
        }
        return Expression.interpolate(Expression.linear(), Expression.lineProgress(), *out)
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private class Chain(private val style: Style, private var above: String?, private val below: String?) {
        val last: String? get() = above
        fun add(layer: Layer) {
            val a = above
            when {
                a != null -> style.addLayerAbove(layer, a)
                below != null -> style.addLayerBelow(layer, below)
                else -> style.addLayer(layer)
            }
            above = layer.id
        }
    }

    private fun firstExisting(style: Style, vararg ids: String?): String? {
        for (id in ids) if (id != null && style.getLayer(id) != null) return id
        return null
    }

    private fun loaded(s: Style): Boolean = try { s.isFullyLoaded } catch (_: Throwable) { false }

    private fun live(): Boolean {
        val s = style ?: return false
        if (loaded(s)) return true
        dropRefs()
        return false
    }

    private fun dropRefs() {
        style = null
        for (r in ribbons) {
            r.source = null
            Arrays.fill(r.layers, null)
            r.appliedValid = false
        }
        farSource = null
        altSource = null
        arrowSource = null
        arrowHeadSource = null
        pinSource = null
    }

    private fun setJson(src: GeoJsonSource?, json: String) {
        if (src == null || !live()) return
        try {
            src.setGeoJson(json)
        } catch (t: Throwable) {
            Log.w(TAG, "setGeoJson failed: ${t.javaClass.simpleName}")
        }
    }

    private fun lineJson(buf: CoordBuf): String {
        if (buf.n < 2) return EMPTY
        sb.setLength(0)
        appendLine(buf, "")
        return sb.toString()
    }

    private fun appendLine(buf: CoordBuf, props: String) {
        sb.append("{\"type\":\"Feature\",\"properties\":{").append(props)
            .append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        for (i in 0 until buf.n) {
            if (i > 0) sb.append(',')
            sb.append('[').append(buf.lng(i)).append(',').append(buf.lat(i)).append(']')
        }
        sb.append("]}}")
    }

    private fun appendPoint(p: GeoPoint, props: String) {
        sb.append("{\"type\":\"Feature\",\"properties\":{").append(props)
            .append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(p.lng).append(',').append(p.lat).append("]}}")
    }

    private fun levelProp(lv: Int): String = when (lv) {
        SpanSet.JAM -> "\"level\":\"jam\""
        SpanSet.SLOW -> "\"level\":\"slow\""
        else -> "\"level\":\"none\""
    }

    private fun sameRoutes(a: List<NavRoute>, b: List<NavRoute>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }
}

/** Line widths and gradients of SPEC §4 (dp; Wz = exponential 1.5, W2 = base 2). */
internal object GlassLines {
    val BODY = doubleArrayOf(12.0, 4.0, 14.0, 7.0, 16.0, 12.0, 17.0, 16.0, 18.0, 22.0, 20.0, 42.0)
    val SHAFT = doubleArrayOf(15.0, 5.5, 16.0, 11.0, 17.0, 22.0, 18.0, 44.0, 19.0, 88.0)
    val HEAD = doubleArrayOf(15.0, 0.175, 16.0, 0.35, 17.0, 0.7, 18.0, 1.4, 19.0, 2.8)

    fun wz(stops: DoubleArray, k: Double = 1.0, add: Double = 0.0): Expression = zoom(1.5, stops, k, add)
    fun w2(stops: DoubleArray, k: Double = 1.0): Expression = zoom(2.0, stops, k, 0.0)

    private fun zoom(base: Double, zv: DoubleArray, k: Double, add: Double): Expression {
        val s = Array(zv.size / 2) { i -> Expression.stop(zv[2 * i], round2(zv[2 * i + 1] * k + add)) }
        return Expression.interpolate(Expression.exponential(base), Expression.zoom(), *s)
    }

    /** Two decimals, as the mock's layer dump. */
    fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    fun rgba(c: Int, a: Double): Expression =
        Expression.rgba((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, a.coerceIn(0.0, 1.0))

    /** 0→clear, at→c, 1→c. */
    fun fadeIn(c: Int, at: Double): Expression = Expression.interpolate(Expression.linear(), Expression.lineProgress(),
        Expression.stop(0.0, rgba(c, 0.0)), Expression.stop(at, rgba(c, 1.0)), Expression.stop(1.0, rgba(c, 1.0)))

    /** 0→c, at→c, 1→clear. */
    fun fadeOut(c: Int, at: Double): Expression = Expression.interpolate(Expression.linear(), Expression.lineProgress(),
        Expression.stop(0.0, rgba(c, 1.0)), Expression.stop(at, rgba(c, 1.0)), Expression.stop(1.0, rgba(c, 0.0)))
}

// ── Pure ribbon maths (no Android / MapLibre types; exercised by the JVM harness) ─────────────────

/** Growable list of (lng, lat) points. */
internal class CoordBuf {
    private var xy = DoubleArray(512)
    var n = 0
        private set

    fun clear() { n = 0 }

    fun add(lng: Double, lat: Double) {
        if (2 * n + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
        xy[2 * n] = lng
        xy[2 * n + 1] = lat
        n++
    }

    fun lng(i: Int): Double = xy[2 * i]
    fun lat(i: Int): Double = xy[2 * i + 1]
}

/**
 * Route polyline with haversine metres (the guidance engine's along-route measure) and Web-Mercator length,
 * which is what MapLibre's line-progress is proportional to (geojson-vt measures projected distance).
 */
internal class RibbonTrack private constructor(val route: NavRoute) {
    val size = route.points.size
    val lat = DoubleArray(size) { route.points[it].lat }
    val lng = DoubleArray(size) { route.points[it].lng }
    val cum = DoubleArray(size)
    private val merc = DoubleArray(size)
    val length: Double
    private val mercPerM: Double
    private val tmp = DoubleArray(2)
    private val tmp2 = DoubleArray(2)

    init {
        if (size > 0) {
            var px = mercX(lng[0])
            var py = mercY(lat[0])
            for (i in 1 until size) {
                cum[i] = cum[i - 1] + RouteTrack.haversine(lat[i - 1], lng[i - 1], lat[i], lng[i])
                val x = mercX(lng[i])
                val y = mercY(lat[i])
                merc[i] = merc[i - 1] + hypot(x - px, y - py)
                px = x
                py = y
            }
        }
        length = if (size > 0) cum[size - 1] else 0.0
        mercPerM = if (length > 0.0) merc[size - 1] / length else 0.0
    }

    /** Largest segment start i ≤ size − 2 with cum[i] ≤ s. */
    fun seg(s: Double): Int {
        val last = size - 2
        if (last <= 0) return 0
        if (s >= cum[last]) return last
        if (s <= 0.0) return 0
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        return lo
    }

    /** Projected distance from the start at along-route metres [s]; linear beyond the ends. */
    fun mercAt(s: Double): Double {
        if (size < 2) return 0.0
        if (s <= 0.0) return s * mercPerM
        if (s >= length) return merc[size - 1] + (s - length) * mercPerM
        val i = seg(s)
        val d = cum[i + 1] - cum[i]
        return if (d > 0.0) merc[i] + (merc[i + 1] - merc[i]) * (s - cum[i]) / d else merc[i]
    }

    /** (lat, lng) at along-route metres [s], clamped to the route. */
    fun pointAt(s: Double, out: DoubleArray) {
        if (size == 0) { out[0] = 0.0; out[1] = 0.0; return }
        if (size == 1) { out[0] = lat[0]; out[1] = lng[0]; return }
        val c = s.coerceIn(0.0, length)
        val i = seg(c)
        val d = cum[i + 1] - cum[i]
        val f = if (d > 0.0) ((c - cum[i]) / d).coerceIn(0.0, 1.0) else 0.0
        out[0] = lat[i] + (lat[i + 1] - lat[i]) * f
        out[1] = lng[i] + (lng[i + 1] - lng[i]) * f
    }

    /** The route between metres [a] and [b] (clamped): exact end points, no zero-length steps; empty if degenerate. */
    fun slice(a: Double, b: Double, out: CoordBuf) {
        out.clear()
        if (size < 2 || length <= 0.0) return
        val s0 = a.coerceIn(0.0, length)
        val s1 = b.coerceIn(0.0, length)
        if (s1 - s0 < MIN_SLICE_M) return
        pointAt(s0, tmp)
        out.add(tmp[1], tmp[0])
        var last = s0
        var j = seg(s0) + 1
        while (j < size && cum[j] < s1) {
            if (cum[j] - last >= DUP_M) {
                out.add(lng[j], lat[j])
                last = cum[j]
            }
            j++
        }
        if (s1 - last >= DUP_M) {
            pointAt(s1, tmp)
            out.add(tmp[1], tmp[0])
        }
        if (out.n < 2) out.clear()
    }

    /** Turn-arrow shaft (turn − back … turn + fwd) and head (lat, lng, bearing over [from, from + span] after the turn). */
    fun arrow(turnM: Double, back: Double, fwd: Double, headAt: Double, from: Double, span: Double, shaft: CoordBuf, head: DoubleArray) {
        slice(turnM - back, turnM + fwd, shaft)
        pointAt(min(turnM + headAt, length), tmp)
        head[0] = tmp[0]
        head[1] = tmp[1]
        val b1 = min(turnM + from + span, length)
        val b0 = max(0.0, min(turnM + from, b1 - span))
        pointAt(b0, tmp)
        pointAt(b1, tmp2)
        head[2] = RouteTrack.bearing(tmp[0], tmp[1], tmp2[0], tmp2[1])
    }

    companion object {
        private const val MIN_SLICE_M = 0.5
        private const val DUP_M = 0.01

        fun of(route: NavRoute): RibbonTrack = RibbonTrack(route)

        fun mercX(lng: Double): Double = (lng + 180.0) / 360.0

        fun mercY(lat: Double): Double {
            val phi = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
            return 0.5 - ln(tan(PI / 4 + phi / 2)) / (2 * PI)
        }
    }
}

/** Congestion spans as along-route metres [a, b), sorted by start. */
internal class SpanSet private constructor(val a: DoubleArray, val b: DoubleArray, val level: IntArray) {
    val size: Int get() = a.size

    companion object {
        const val NONE = 0
        const val SLOW = 1
        const val JAM = 2
        private const val MIN_SPAN_M = 1.0

        fun of(track: RibbonTrack, spans: List<CongestionSpan>): SpanSet? {
            if (spans.isEmpty() || track.size < 2) return null
            val last = track.size - 1
            val aa = DoubleArray(spans.size)
            val bb = DoubleArray(spans.size)
            val ll = IntArray(spans.size)
            var k = 0
            for (s in spans) {
                val i0 = s.startIndex.coerceIn(0, last)
                val i1 = s.endIndex.coerceIn(0, last)
                val x = track.cum[min(i0, i1)]
                val y = track.cum[max(i0, i1)]
                if (y - x < MIN_SPAN_M) continue
                aa[k] = x
                bb[k] = y
                ll[k] = if (s.level == CongestionLevel.JAM) JAM else SLOW
                k++
            }
            if (k == 0) return null
            val order = (0 until k).sortedBy { aa[it] }
            return SpanSet(DoubleArray(k) { aa[order[it]] }, DoubleArray(k) { bb[order[it]] }, IntArray(k) { ll[order[it]] })
        }
    }
}

/** Ascending, non-overlapping [u, v) intervals with a congestion level. */
internal class Pieces {
    var u = DoubleArray(16)
    var v = DoubleArray(16)
    var level = IntArray(16)
    var n = 0

    fun clear() { n = 0 }

    fun add(x0: Double, x1: Double, lv: Int) {
        if (n > 0 && level[n - 1] == lv && abs(v[n - 1] - x0) < 1e-9) { v[n - 1] = x1; return }
        if (n == u.size) {
            u = u.copyOf(n * 2); v = v.copyOf(n * 2); level = level.copyOf(n * 2)
        }
        u[n] = x0; v[n] = x1; level[n] = lv
        n++
    }
}

internal class PieceBuilder {
    private var marks = DoubleArray(32)

    /** Levels over [x0, x1] from spans starting before [before] (NaN: none); jam wins where spans overlap. */
    fun build(set: SpanSet, x0: Double, x1: Double, before: Double, out: Pieces) {
        out.clear()
        if (!(x1 > x0)) return
        if (marks.size < 2 * set.size + 2) marks = DoubleArray(2 * set.size + 2)
        var m = 0
        marks[m++] = x0
        marks[m++] = x1
        for (k in 0 until set.size) {
            if (!(set.a[k] < before) || set.b[k] <= x0 || set.a[k] >= x1) continue
            if (set.a[k] > x0) marks[m++] = set.a[k]
            if (set.b[k] < x1) marks[m++] = set.b[k]
        }
        Arrays.sort(marks, 0, m)
        // Both span starts and interval midpoints are ordered. For each level,
        // only the furthest end among spans started by the midpoint matters:
        // an earlier span of that level cannot cover a point beyond that end.
        var next = 0
        var slowEnd = Double.NEGATIVE_INFINITY
        var jamEnd = Double.NEGATIVE_INFINITY
        for (q in 0 until m - 1) {
            val p = marks[q]
            val r = marks[q + 1]
            if (r - p < 1e-6) continue
            val mid = 0.5 * (p + r)
            while (next < set.size && set.a[next] < before && set.a[next] <= mid) {
                val end = set.b[next]
                if (set.level[next] == SpanSet.JAM) {
                    if (end > jamEnd) jamEnd = end
                } else if (set.level[next] == SpanSet.SLOW) {
                    if (end > slowEnd) slowEnd = end
                }
                next++
            }
            val lv = when {
                jamEnd > mid -> SpanSet.JAM
                slowEnd > mid -> SpanSet.SLOW
                else -> SpanSet.NONE
            }
            if (lv != SpanSet.NONE) out.add(p, r, lv)
        }
    }
}

/** line-gradient stops: strictly ascending positions, colour slot (SpanSet level) and alpha. */
internal class Stops {
    var t = DoubleArray(16)
    var slot = IntArray(16)
    var a = DoubleArray(16)
    var n = 0

    fun clear() { n = 0 }

    fun add(pos: Double, s: Int, alpha: Double) {
        if (n > 0 && pos <= t[n - 1]) return
        if (n == t.size) {
            t = t.copyOf(n * 2); slot = slot.copyOf(n * 2); a = a.copyOf(n * 2)
        }
        t[n] = pos; slot[n] = s; a[n] = alpha
        n++
    }

    fun sameAs(o: Stops): Boolean {
        if (n != o.n) return false
        for (i in 0 until n) {
            if (slot[i] != o.slot[i] || abs(t[i] - o.t[i]) > 1e-9 || abs(a[i] - o.a[i]) > 1e-6) return false
        }
        return true
    }

    fun copyFrom(o: Stops) {
        if (t.size < o.n) { t = DoubleArray(o.t.size); slot = IntArray(o.t.size); a = DoubleArray(o.t.size) }
        System.arraycopy(o.t, 0, t, 0, o.n)
        System.arraycopy(o.slot, 0, slot, 0, o.n)
        System.arraycopy(o.a, 0, a, 0, o.n)
        n = o.n
    }
}

internal class GradientBuilder {
    private var events = DoubleArray(16)

    /**
     * Alpha 0 up to [p0In], linear to 1 at [p1In] (NaN: no trim), over colour [pieces] in line-progress units.
     * Colour changes are hard steps; the clear part takes the colour at p0 so the fade never bleeds.
     * [texel] > 0 pre-distorts for a ramp of that resolution (see [RAMP_TEXEL]); 0 gives the exact stops.
     */
    fun trimStops(p0In: Double, p1In: Double, pieces: Pieces, out: Stops, texel: Double = 0.0) {
        out.clear()
        // Sampling shifts the ramp by half a texel; its linear filtering rounds each knot over ±half a texel.
        val shift = texel * 0.5
        val inset = min(texel * 0.5, (p1In - p0In) * 0.45)
        val p0 = p0In - shift + inset
        val p1 = p1In - shift - inset
        val trim = !p0.isNaN() && !p1.isNaN() && p1 > p0
        if (events.size < 2 * pieces.n + 2) events = DoubleArray(2 * pieces.n + 2)
        for (k in 0 until pieces.n) { pieces.u[k] -= shift; pieces.v[k] -= shift }
        var m = 0
        for (k in 0 until pieces.n) { events[m++] = pieces.u[k]; events[m++] = pieces.v[k] }
        if (trim) { events[m++] = p0; events[m++] = p1 }
        Arrays.sort(events, 0, m)
        val clearSlot = if (trim) slotAt(pieces, max(0.0, p0)) else 0
        val a0 = alpha(trim, p0, p1, 0.0)
        out.add(0.0, if (a0 <= 0.0) clearSlot else slotAt(pieces, 0.0), a0)
        var prev = Double.NaN
        for (q in 0 until m) {
            val e = events[q]
            if (!(e > 0.0 && e < 1.0) || e == prev) continue
            prev = e
            if (trim && e <= p0) {
                if (e == p0) out.add(e, clearSlot, 0.0)
                continue
            }
            val before = slotBefore(pieces, e)
            val after = slotAt(pieces, e)
            if (before != after) out.add(e - STEP, before, alpha(trim, p0, p1, e - STEP))
            out.add(e, after, alpha(trim, p0, p1, e))
        }
        out.add(1.0, slotBefore(pieces, 1.0), alpha(trim, p0, p1, 1.0))
    }

    private fun alpha(trim: Boolean, p0: Double, p1: Double, t: Double): Double =
        if (!trim) 1.0 else ((t - p0) / (p1 - p0)).coerceIn(0.0, 1.0)

    private fun slotAt(p: Pieces, t: Double): Int {
        for (k in 0 until p.n) if (p.u[k] <= t && t < p.v[k]) return p.level[k]
        return SpanSet.NONE
    }

    private fun slotBefore(p: Pieces, t: Double): Int {
        for (k in 0 until p.n) if (p.u[k] < t && t <= p.v[k]) return p.level[k]
        return SpanSet.NONE
    }

    companion object {
        private const val STEP = 1e-6
        // MapLibre Native samples line-gradient at k/256 into texel k and reads it filtered at (k + .5)/256.
        const val RAMP_TEXEL = 1.0 / 256.0
    }
}

/** Progress, window, congestion cut and far-ribbon rules of the route ribbon. Times are elapsedRealtime ms. */
internal class RibbonPlan {
    var track: RibbonTrack? = null
        private set
    var windowed = false
        private set
    /** Car position along [track] (m), NaN until known; never decreases for one track. */
    var progress = Double.NaN
        private set
    var sliceCar = Double.NaN
        private set
    var sliceAtMs = 0L
        private set
    /** Congestion is drawn from here on; behind it the ribbon itself carries the congestion colours. */
    var cut = Double.NaN
        private set
    var plannedW0 = 0.0
        private set
    var plannedW1 = 0.0
        private set
    var farStart = Double.NaN
        private set
    var farEnd = Double.NaN
        private set
    var farVersion = 0
        private set
    var trimCar = Double.NaN

    fun reset(t: RibbonTrack?, windowed: Boolean) {
        track = t
        this.windowed = windowed
        progress = Double.NaN
        rearm()
    }

    fun setWindowed(w: Boolean) {
        windowed = w
        rearm()
    }

    fun clearProgress() {
        progress = Double.NaN
        rearm()
    }

    private fun rearm() {
        sliceCar = Double.NaN
        cut = Double.NaN
        trimCar = Double.NaN
        farStart = Double.NaN
        farEnd = Double.NaN
        farVersion++
    }

    /** False for NaN/∞ (off route), no track, or no forward movement: the trim holds. */
    fun advance(alongM: Double): Boolean {
        val t = track ?: return false
        if (alongM.isNaN() || alongM.isInfinite()) return false
        val s = alongM.coerceIn(0.0, t.length)
        if (!progress.isNaN() && s <= progress) return false
        progress = s
        return true
    }

    /** Window and cut for geometry cut now: whole route, or car − 60 m … car + 2.5 km (start of route if unknown). */
    fun planWindow(nowMs: Long) {
        val t = track
        val len = t?.length ?: 0.0
        val c = progress
        if (t == null || !windowed) {
            plannedW0 = 0.0
            plannedW1 = len
        } else {
            val car = if (c.isNaN()) 0.0 else c
            plannedW0 = max(0.0, car - WINDOW_BACK_M)
            plannedW1 = min(len, car + WINDOW_AHEAD_M)
        }
        sliceCar = c
        sliceAtMs = nowMs
        cut = if (c.isNaN()) Double.NaN else c + CUT_LEAD_M
        planFar()
    }

    /** Per frame, allocation-free: the car moved far enough for new geometry (or the window is about to run out). */
    fun sliceWanted(activeW1: Double): Boolean {
        if (progress.isNaN()) return false
        if (sliceCar.isNaN() || urgent(activeW1)) return true
        return progress - sliceCar >= SLICE_MIN_M
    }

    /** At most once a second, unless the trim would leave the window. */
    fun sliceAllowed(nowMs: Long, activeW1: Double): Boolean =
        sliceCar.isNaN() || urgent(activeW1) || nowMs - sliceAtMs >= SLICE_MIN_MS

    private fun urgent(activeW1: Double): Boolean {
        val len = track?.length ?: return false
        return windowed && activeW1 < len - END_EPS_M && progress - TRIM_FULL_M > activeW1 - URGENT_MARGIN_M
    }

    fun trimDue(): Boolean = !progress.isNaN() && (trimCar.isNaN() || progress - trimCar >= TRIM_STEP_M)

    /** Trim knots p0 (alpha 0) and p1 (alpha 1) in the line-progress of a window with projected ends [m0], [m1]. */
    fun trimKnots(t: RibbonTrack, m0: Double, m1: Double, out: DoubleArray) {
        val span = m1 - m0
        if (progress.isNaN() || span <= 0.0) { out[0] = Double.NaN; out[1] = Double.NaN; return }
        out[0] = (t.mercAt(progress - TRIM_CLEAR_M) - m0) / span
        out[1] = (t.mercAt(progress - TRIM_FULL_M) - m0) / span
    }

    /** End of the drawn ribbon: congestion beyond it has no route under it. */
    val ribbonEnd: Double
        get() {
            val t = track ?: return 0.0
            return when {
                !windowed -> t.length
                !farStart.isNaN() -> farEnd
                else -> plannedW1
            }
        }

    // Far ribbon: from car + 1.2 km, re-planned 500 m before the car reaches its start; never a gap to the window.
    // Only with a known car position: planned from the route start it would cover a restored trip's travelled part.
    private fun planFar() {
        val t = track
        if (t == null || !windowed || progress.isNaN() || plannedW1 >= t.length - END_EPS_M) {
            if (!farStart.isNaN()) { farStart = Double.NaN; farEnd = Double.NaN; farVersion++ }
            return
        }
        val car = progress
        if (!farStart.isNaN() && car < farStart - FAR_REFRESH_M && farStart <= plannedW1) return
        farStart = min(car + FAR_LEAD_M, plannedW1)
        farEnd = min(t.length, farStart + FAR_SPAN_M)
        farVersion++
    }

    companion object {
        const val WINDOW_BACK_M = 60.0
        const val WINDOW_AHEAD_M = 2500.0
        const val TRIM_CLEAR_M = 46.0
        const val TRIM_FULL_M = 3.0
        const val TRIM_STEP_M = 2.0
        const val SLICE_MIN_M = 25.0
        const val SLICE_MIN_MS = 1000L
        // Half the travel between slices, so the congestion cut swings ±12 m around the car, under the puck.
        const val CUT_LEAD_M = 12.0
        const val URGENT_MARGIN_M = 200.0
        const val FAR_LEAD_M = 1200.0
        const val FAR_REFRESH_M = 500.0
        const val FAR_SPAN_M = 20_000.0
        const val END_EPS_M = 1.0
    }
}
