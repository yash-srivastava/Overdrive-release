package com.overdrive.app.ui.timeline

data class TlSegment(
    val id: String,
    val type: String,
    val start: Long,
    val end: Long?,
    val spanSrc: String,
    val available: Boolean,
    val storage: String?,
    val cameraId: Int,
    val layout: String?,
    val sizeBytes: Long,
    val mp4Mtime: Long,
    val fp: String,
    val videoUrl: String,
    val thumbUrl: String,
    val eventUrl: String,
    val peakSeverity: String?,
    val classes: List<String>,
    val counts: Map<String, Int>,
    val peakConfidence: Double?,
    val cameras: String?,
    val place: String?,
    val parkingSessionId: String?,
    val watched: Boolean,
    /** Not analysed by the daemon yet: an unknown end means "later", not "damaged". */
    val indexing: Boolean = false,
)

data class TlMark(
    val rec: String,
    val t0: Long,
    val t1: Long,
    val kind: String,
    val conf: Double?,
    val cam: String?,
    val alert: Boolean,
)

data class TlSession(
    val id: String,
    val start: Long,
    val end: Long?,
    val place: String?,
    val safeZone: String?,
    val sentry: String,
)

data class TlTrip(
    val id: Long,
    val start: Long,
    val end: Long?,
    val distanceKm: Double?,
)

data class TlState(
    val start: Long,
    val end: Long?,
    val kind: String,
    val state: String,
    val reason: String,
)

data class TlReaped(
    val start: Long,
    val end: Long,
    val type: String,
    val reason: String,
)

data class TlRoad(
    val t: Long,
    val kind: String,
    val severity: String,
)

data class TlLive(
    val type: String,
    val since: Long,
)

data class TlWindow(
    val from: Long,
    val to: Long,
    val now: Long,
    val tzId: String,
    val segments: List<TlSegment>,
    val marks: List<TlMark>,
    val sessions: List<TlSession>,
    val trips: List<TlTrip>,
    val states: List<TlState>,
    val reaped: List<TlReaped>,
    val road: List<TlRoad>,
    val live: List<TlLive>,
    val truncated: Boolean,
)

data class TlDay(
    val day: String,
    val footageMs: Long,
    val events: Int,
    val alerts: Int,
)

data class ClipSpec(
    val layout: com.overdrive.app.ui.view.ZoomableVideoView.Layout?,
    val lens: com.overdrive.app.ui.view.PlaybackDewarp.Spec?,
    val single: Boolean,
    /** Sidecar "grid" block: the clip can be recomposed as the Dashcam view. */
    val grid: GridSpec? = null,
)

/**
 * Sidecar "grid" geometry (contract v1). [cells]: picture-space (y down) tile origins of the
 * bottom-band thirds, x/y pairs left to right. k1/k2: lens at parse time (0 when not raw).
 */
class GridSpec(
    val width: Int,
    val height: Int,
    val aspect: Float,
    val raw: Boolean,
    val stripFrac: Float,
    val split: Float,
    val cells: FloatArray,
    val k1: Float,
    val k2: Float,
) {
    companion object {
        private val ROLES = listOf("front", "right", "rear", "left")

        /** Null unless the block passes every check of the contract's parsing rule. */
        fun parse(json: org.json.JSONObject?, strength: Int): GridSpec? {
            val g = json?.optJSONObject("grid") ?: return null
            if (g.optInt("v", 0) != 1) return null
            val frame = g.optJSONObject("frame") ?: return null
            val w = frame.optInt("width", 0)
            val h = frame.optInt("height", 0)
            if (w !in 2..8192 || h !in 2..8192) return null
            val tilesArr = g.optJSONArray("tiles") ?: return null
            if (tilesArr.length() != 4) return null
            val tiles = (0 until 4).map { tilesArr.optString(it, "") }
            if (tiles.toSet() != ROLES.toSet()) return null
            val aspect = g.optDouble("aspect", Double.NaN).toFloat()
            if (!(aspect in 0.25f..1.0f)) return null
            val dash = g.optJSONObject("dashcam") ?: return null
            val split = dash.optDouble("split", Double.NaN).toFloat()
            if (!(split > 0f && split < 1f)) return null
            val cellsArr = dash.optJSONArray("cells") ?: return null
            if (cellsArr.length() != 3) return null
            val cells = FloatArray(6)
            for (c in 0 until 3) {
                val i = tiles.indexOf(cellsArr.optString(c, ""))
                if (i < 0) return null
                cells[2 * c] = (i % 2) * 0.5f
                cells[2 * c + 1] = (i / 2) * 0.5f
            }
            val raw = g.optBoolean("raw", false)
            val strip = g.optInt("strip", 0)
            val stripFrac = if (strip > 0 && strip * 2 < h) strip.toFloat() / h else 0f
            val t = if (raw) strength.coerceIn(0, 100) / 100f else 0f
            return GridSpec(w, h, aspect, raw, stripFrac, split, cells, 0.30f * t, 0.10f * t)
        }
    }
}
