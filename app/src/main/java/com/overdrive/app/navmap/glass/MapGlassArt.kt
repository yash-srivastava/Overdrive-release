package com.overdrive.app.navmap.glass

import android.content.Context
import android.graphics.Bitmap
import com.overdrive.app.navmap.glass.art.ArtKit
import com.overdrive.app.navmap.glass.art.MarkerArt
import com.overdrive.app.navmap.glass.art.PuckArt
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LayoutPropertyValue
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.layers.PropertyValue
import java.util.WeakHashMap

/**
 * Every map bitmap of the glass navigation UI (mock SPEC §4.4, §5), drawn with Canvas gradient fills only and
 * registered with bitmap.density = densityDpi, so MapLibre sizes them in dp. Fixed images are baked once per
 * theme and density; counts and stop numbers are drawn when the style first asks for them.
 */
object MapGlassArt {
    const val PUCK = "od-puck"
    const val PUCK_SHADOW = "od-puck-shadow"
    const val HEAD = "od-head"
    const val HEAD_RIM = "od-head-rim"
    const val HEAD_SHADOW = "od-head-shadow"
    const val CONTACT = "od-contact"
    const val PIN_DEST = "od-pin-dest"
    const val GEM_PREFIX = "od-gem-"
    const val CLUSTER_PREFIX = "od-cluster-"
    const val VIA_PREFIX = "od-via-"
    const val CLUSTER_MAX = "od-cluster-99+"

    const val KIND_POTHOLE = "pothole"
    const val KIND_BREAKER = "breaker"
    const val KIND_ROUGH = "rough"
    const val KIND_UNKNOWN = "unknown"

    // LocationIndicatorLayer paint values for od-puck / od-puck-shadow (tuned for the follow camera's 55–57° tilt).
    /**
     * bearing-image-size / shadow-image-size by zoom: the 92 dp image (64 dp chevron) at 1:1 from z16.5, and 0.9 at the
     * 130 km/h drive zoom (15.3), so the car keeps its size as the camera zooms out with speed; overview zooms shrink it.
     */
    private val PUCK_ZOOM = doubleArrayOf(11.0, 0.42, 13.0, 0.6, 14.5, 0.82, 15.5, 0.92, 16.5, 1.0)
    /** image-tilt-displacement: drops the shadow D·pitch(rad)·cos(pitch) ≈ 12 dp down-screen at 56°, where the mock's pool sits. */
    const val PUCK_TILT_DISPLACEMENT = 21.8f
    /** perspective-compensation: 0.5 scales off-centre like the mock's map-pitched icons (0.5 + 0.5·distance ratio). */
    const val PUCK_PERSPECTIVE_COMPENSATION = 0.5f

    private val KIND_NAMES = arrayOf(KIND_POTHOLE, KIND_BREAKER, KIND_ROUGH, KIND_UNKNOWN)
    private val GEM_IDS = Array(4) { kind -> Array(3) { sev -> "$GEM_PREFIX${KIND_NAMES[kind]}-${sev + 1}" } }
    private val CLUSTER_IDS = Array(100) { "$CLUSTER_PREFIX$it" }
    private val VIA_IDS = Array(VIA_IDS_CACHED + 1) { "$VIA_PREFIX$it" }
    private const val VIA_IDS_CACHED = 32
    private const val OVERFLOW = "99+"

    /** Today's hazard feature `type`: 1 pothole, 0 breaker, 3 rough, anything else unknown. */
    fun kindOf(type: Int): String = when (type) {
        1 -> KIND_POTHOLE
        0 -> KIND_BREAKER
        3 -> KIND_ROUGH
        else -> KIND_UNKNOWN
    }

    /** od-gem-{kind}-{sev}; an unknown kind reads as unknown and sev is clamped to 1–3. */
    fun gemId(kind: String, sev: Int): String = GEM_IDS[kindIndex(kind)][sev.coerceIn(1, 3) - 1]

    /** od-cluster-N for N ≤ 99, else od-cluster-99+. */
    fun clusterId(n: Int): String = if (n > 99) CLUSTER_MAX else CLUSTER_IDS[n.coerceAtLeast(0)]

    fun viaId(n: Int): String = if (n in 0..VIA_IDS_CACHED) VIA_IDS[n] else "$VIA_PREFIX$n"

    /** The recommended LocationIndicatorLayer look: images and the paint values above (location, bearing, accuracy are main's). */
    fun puckLayerProps(): Array<PropertyValue<*>> = arrayOf(
        LayoutPropertyValue("bearing-image", PUCK),
        LayoutPropertyValue("shadow-image", PUCK_SHADOW),
        LayoutPropertyValue("top-image", ""),
        PaintPropertyValue("bearing-image-size", MapGlassLayers.byZoom(PUCK_ZOOM)),
        PaintPropertyValue("shadow-image-size", MapGlassLayers.byZoom(PUCK_ZOOM)),
        PaintPropertyValue("image-tilt-displacement", PUCK_TILT_DISPLACEMENT),
        PaintPropertyValue("perspective-compensation", PUCK_PERSPECTIVE_COMPENSATION),
    )

    /**
     * Adds every fixed image (puck, head, contact, gems, destination) to [style]. Idempotent per style, theme and
     * density; a new theme replaces them and redraws the counts and stop numbers this style already holds.
     */
    @Synchronized
    fun registerStatic(context: Context, style: Style, night: Boolean) {
        if (!style.isFullyLoaded) return
        val dpi = context.resources.displayMetrics.densityDpi
        val reg = styles.getOrPut(style) { Registration() }
        if (reg.registered && reg.night == night && reg.dpi == dpi) return
        val sheet = sheet(night, dpi)
        style.addImages(sheet.images)
        reg.registered = true
        reg.night = night
        reg.dpi = dpi
        for (id in reg.onDemand) draw(context, sheet, id)?.let { style.addImage(id, it) }
    }

    /** OnStyleImageMissingListener: draws od-cluster-N / od-via-N (and any od- image not added yet); false for other ids. */
    @Synchronized
    fun drawMissing(context: Context, style: Style, id: String, night: Boolean): Boolean {
        if (!id.startsWith("od-") || !style.isFullyLoaded) return false
        val sheet = sheet(night, context.resources.displayMetrics.densityDpi)
        val bmp = draw(context, sheet, id) ?: return false
        style.addImage(id, bmp)
        if (!sheet.images.containsKey(id)) styles.getOrPut(style) { Registration() }.onDemand.add(id)
        return true
    }

    /** The bitmap [registerStatic] / [drawMissing] would add for [id], or null if the id is not ours (previews). */
    @Synchronized
    internal fun bitmap(context: Context, id: String, night: Boolean): Bitmap? =
        draw(context, sheet(night, context.resources.displayMetrics.densityDpi), id)

    // ── caches ───────────────────────────────────────────────────────────────

    /** One theme at one density: the fixed images, and the layers the count and stop-number images are composed from. */
    private class Sheet(val night: Boolean, val dpi: Int) {
        val k = dpi / 160f
        val images = HashMap<String, Bitmap>(24)
        var clusterBack: Bitmap? = null
        var clusterFront: Bitmap? = null
        var viaBack: Bitmap? = null
        var viaFront: Bitmap? = null
    }

    private class Registration {
        var registered = false
        var night = false
        var dpi = 0
        val onDemand = HashSet<String>()
    }

    // the head unit and the cluster may differ in theme or density; two sheets cover both
    private val sheets = ArrayList<Sheet>(2)
    private val styles = WeakHashMap<Style, Registration>()

    private fun sheet(night: Boolean, dpi: Int): Sheet {
        for (i in sheets.indices) {
            val s = sheets[i]
            if (s.night == night && s.dpi == dpi) return s
        }
        val s = Sheet(night, dpi)
        val p = MapGlassPalette.of(night)
        val t = p.theme
        val k = s.k
        s.images[PUCK] = PuckArt.puck(t, k, dpi)
        s.images[PUCK_SHADOW] = PuckArt.shadow(t, k, dpi)
        s.images[HEAD] = PuckArt.head(p, PuckArt.HEAD_BODY, k, dpi)
        s.images[HEAD_RIM] = PuckArt.head(p, PuckArt.HEAD_RIM, k, dpi)
        s.images[HEAD_SHADOW] = PuckArt.head(p, PuckArt.HEAD_SHADOW, k, dpi)
        s.images[CONTACT] = MarkerArt.contact(t, k, dpi)
        s.images[PIN_DEST] = MarkerArt.destination(t, k, dpi)
        for (kind in 0 until 4) for (sev in 1..3) s.images[GEM_IDS[kind][sev - 1]] = MarkerArt.gem(t, kind, sev, k, dpi)
        if (sheets.size >= 2) sheets.removeAt(0)
        sheets.add(s)
        return s
    }

    private fun kindIndex(kind: String): Int = when (kind) {
        KIND_POTHOLE -> MarkerArt.POTHOLE
        KIND_BREAKER -> MarkerArt.BREAKER
        KIND_ROUGH -> MarkerArt.ROUGH
        else -> MarkerArt.UNKNOWN
    }

    // ── on-demand images ─────────────────────────────────────────────────────

    private fun draw(context: Context, s: Sheet, id: String): Bitmap? {
        s.images[id]?.let { return it }
        return when {
            id.startsWith(CLUSTER_PREFIX) -> cluster(context, s, id)
            id.startsWith(VIA_PREFIX) -> via(context, s, id)
            id.startsWith(GEM_PREFIX) -> oddGem(s, id)
            else -> null
        }
    }

    // the count is drawn straight from the id's digits; more than 99 reads 99+
    private fun cluster(context: Context, s: Sheet, id: String): Bitmap? {
        val start = CLUSTER_PREFIX.length
        val overflow = id.length == start + 3 && id.regionMatches(start, OVERFLOW, 0, 3)
        val n = if (overflow) 100 else digits(id, start)
        if (n < 0) return null
        val t = MapGlassPalette.of(s.night).theme
        val back = s.clusterBack ?: MarkerArt.clusterBack(t, s.k, s.dpi).also { s.clusterBack = it }
        val front = s.clusterFront ?: MarkerArt.clusterFront(t, s.k, s.dpi).also { s.clusterFront = it }
        val bmp = compose(s, MarkerArt.ORB_SIZE, MarkerArt.ORB_SIZE, back)
        if (n > 99) MarkerArt.clusterLabel(context, ArtKit.canvas, t, OVERFLOW, 0, 3)
        else MarkerArt.clusterLabel(context, ArtKit.canvas, t, id, start, id.length)
        finish(s, front)
        return bmp
    }

    private fun via(context: Context, s: Sheet, id: String): Bitmap? {
        val start = VIA_PREFIX.length
        if (digits(id, start) < 0) return null
        val t = MapGlassPalette.of(s.night).theme
        val back = s.viaBack ?: MarkerArt.viaBack(t, s.k, s.dpi).also { s.viaBack = it }
        val front = s.viaFront ?: MarkerArt.viaFront(t, s.k, s.dpi).also { s.viaFront = it }
        val bmp = compose(s, MarkerArt.PIN_W, MarkerArt.PIN_H, back)
        MarkerArt.viaLabel(context, ArtKit.canvas, t, id, start, id.length)
        finish(s, front)
        return bmp
    }

    // od-gem-{kind}-{sev} with a kind or severity outside the 12 fixed ones
    private fun oddGem(s: Sheet, id: String): Bitmap? {
        val dash = id.lastIndexOf('-')
        if (dash <= GEM_PREFIX.length) return null
        val sev = digits(id, dash + 1)
        if (sev < 0) return null
        val kind = when {
            id.regionMatches(GEM_PREFIX.length, KIND_POTHOLE, 0, KIND_POTHOLE.length) && dash == GEM_PREFIX.length + KIND_POTHOLE.length -> MarkerArt.POTHOLE
            id.regionMatches(GEM_PREFIX.length, KIND_BREAKER, 0, KIND_BREAKER.length) && dash == GEM_PREFIX.length + KIND_BREAKER.length -> MarkerArt.BREAKER
            id.regionMatches(GEM_PREFIX.length, KIND_ROUGH, 0, KIND_ROUGH.length) && dash == GEM_PREFIX.length + KIND_ROUGH.length -> MarkerArt.ROUGH
            else -> MarkerArt.UNKNOWN
        }
        return MarkerArt.gem(MapGlassPalette.of(s.night).theme, kind, sev.coerceIn(1, 3), s.k, s.dpi)
    }

    // a new bitmap holding [back] at 1:1, the shared canvas left in dp for the label
    private fun compose(s: Sheet, w: Float, h: Float, back: Bitmap): Bitmap {
        val bmp = ArtKit.begin(w, h, s.k, s.dpi)
        val c = ArtKit.canvas
        c.save()
        c.scale(1f / s.k, 1f / s.k)
        c.drawBitmap(back, 0f, 0f, null)
        c.restore()
        return bmp
    }

    private fun finish(s: Sheet, front: Bitmap) {
        val c = ArtKit.canvas
        c.save()
        c.scale(1f / s.k, 1f / s.k)
        c.drawBitmap(front, 0f, 0f, null)
        c.restore()
        ArtKit.end()
    }

    // the non-negative number spelled by id[start until end], or -1 if that is empty or not all digits
    private fun digits(id: String, start: Int): Int {
        if (start >= id.length) return -1
        var n = 0
        for (i in start until id.length) {
            val d = id[i] - '0'
            if (d !in 0..9) return -1
            n = if (n > 100_000) n else n * 10 + d
        }
        return n
    }
}
