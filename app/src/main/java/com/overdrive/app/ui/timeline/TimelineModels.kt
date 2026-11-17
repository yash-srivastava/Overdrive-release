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
)
