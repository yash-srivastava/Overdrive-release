package com.overdrive.app.ui.timeline

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.overdrive.app.R
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One Moments row, fully formatted on the main thread before binding. */
data class MomentRow(
    val id: String,
    val fp: String,
    val start: Long,
    val end: Long?,
    val thumbUrl: String,
    val time: String,
    val kind: String,
    val subtitle: String,
    val severity: String?,
    val current: Boolean,
    val playing: Boolean,
    val description: String,
)

/** Moment labelling rules (pure, no Android types). */
object MomentText {
    const val KIND_REPLAY = "replay"
    const val KIND_CLOSE_PASS = "close_pass"
    const val KIND_EVENT = "event"
    const val FILTER_ALL = "all"
    const val FILTER_PEOPLE = "people"
    const val FILTER_VEHICLES = "vehicles"
    const val FILTER_ALERTS = "alerts"

    private val MOMENT_TYPES = setOf("sentry", "proximity", "replay")
    private val ACTOR_ORDER = listOf("person", "vehicle", "bike", "animal")
    private val CAMERAS = setOf("front", "right", "rear", "left")

    enum class Status { NONE, ALERT, CRITICAL, PLAYING }

    fun isMoment(type: String): Boolean = type in MOMENT_TYPES

    fun normalize(raw: String): String? = when (raw.trim().lowercase(Locale.ROOT)) {
        "person", "people", "pedestrian" -> "person"
        "vehicle", "car", "truck", "bus", "van" -> "vehicle"
        "bike", "bicycle", "motorcycle", "motorbike", "cyclist" -> "bike"
        "animal", "dog", "cat", "bird" -> "animal"
        "motion" -> "motion"
        "deterrent" -> "deterrent"
        else -> null
    }

    /** Actor kinds seen in a clip, in display order. */
    fun actorKinds(classes: List<String>, counts: Map<String, Int>, markKinds: Collection<String>): List<String> {
        val present = HashSet<String>()
        for ((k, n) in counts) if (n > 0) normalize(k)?.let(present::add)
        for (c in classes) normalize(c)?.let(present::add)
        for (m in markKinds) normalize(m)?.let(present::add)
        return ACTOR_ORDER.filter { it in present }
    }

    /** Label keys: replay / close pass by type, else up to two actors, else deterrent / motion / event. */
    fun kindKeys(type: String, classes: List<String>, counts: Map<String, Int>, markKinds: Collection<String>): List<String> {
        if (type == "replay") return listOf(KIND_REPLAY)
        if (type == "proximity") return listOf(KIND_CLOSE_PASS)
        val actors = actorKinds(classes, counts, markKinds)
        if (actors.isNotEmpty()) return actors.take(2)
        val other = (markKinds + classes).mapNotNull(::normalize).toSet()
        if ("deterrent" in other) return listOf("deterrent")
        if ("motion" in other) return listOf("motion")
        return listOf(KIND_EVENT)
    }

    fun firstCamera(cameras: String?): String? =
        cameras?.split(',', ';', '|', ' ', '+')
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.map { if (it == "back") "rear" else it }
            ?.firstOrNull { it in CAMERAS }

    /** Clip length as m:ss, or h:mm:ss past an hour; null when unknown. */
    fun durationLabel(ms: Long?): String? {
        if (ms == null || ms < 0L) return null
        val total = (ms + 500L) / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun subtitleParts(camera: String?, place: String?, duration: String?): List<String> =
        listOfNotNull(camera, place?.trim(), duration).filter { it.isNotEmpty() }

    fun status(severity: String?, playing: Boolean): Status = when {
        playing -> Status.PLAYING
        severity.equals("CRITICAL", ignoreCase = true) -> Status.CRITICAL
        severity.equals("ALERT", ignoreCase = true) -> Status.ALERT
        else -> Status.NONE
    }

    fun matchesFilter(filter: String, actors: List<String>, severity: String?): Boolean = when (filter) {
        FILTER_PEOPLE -> "person" in actors
        FILTER_VEHICLES -> "vehicle" in actors || "bike" in actors
        FILTER_ALERTS -> severity.equals("ALERT", true) || severity.equals("CRITICAL", true)
        else -> true
    }

    /** The row the playhead sits in: latest-starting moment covering it. */
    fun currentId(moments: List<TlSegment>, playheadMs: Long, playingId: String?): String? {
        if (playingId != null && moments.any { it.id == playingId }) return playingId
        var best: TlSegment? = null
        for (m in moments) {
            val end = m.end ?: continue
            if (playheadMs in m.start until end && (best == null || m.start > best.start)) best = m
        }
        return best?.id
    }
}

/** Localised text for [MomentText] keys. */
internal object MomentLabels {
    fun kind(ctx: Context, keys: List<String>): String {
        val labels = keys.map { label(ctx, it) }
        return when {
            labels.size >= 2 -> ctx.getString(R.string.timeline_kind_and, labels[0], labels[1])
            labels.isNotEmpty() -> labels[0]
            else -> ctx.getString(R.string.timeline_kind_event)
        }
    }

    fun label(ctx: Context, key: String): String = ctx.getString(
        when (key) {
            "person" -> R.string.timeline_kind_person
            "vehicle" -> R.string.timeline_kind_vehicle
            "bike" -> R.string.timeline_kind_bike
            "animal" -> R.string.timeline_kind_animal
            "motion" -> R.string.timeline_kind_motion
            "deterrent" -> R.string.timeline_kind_deterrent
            MomentText.KIND_REPLAY -> R.string.timeline_moment_replay
            MomentText.KIND_CLOSE_PASS -> R.string.timeline_moment_close_pass
            else -> R.string.timeline_kind_event
        }
    )

    fun camera(ctx: Context, cam: String?): String? = when (cam) {
        "front" -> ctx.getString(R.string.timeline_cam_front)
        "right" -> ctx.getString(R.string.timeline_cam_right)
        "rear" -> ctx.getString(R.string.timeline_cam_rear)
        "left" -> ctx.getString(R.string.timeline_cam_left)
        else -> null
    }

    fun join(ctx: Context, parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        2 -> ctx.getString(R.string.timeline_gap_fmt, parts[0], parts[1])
        else -> ctx.getString(R.string.timeline_moment_subtitle, parts[0], parts[1], parts[2])
    }
}

class MomentsAdapter(
    private val reducedMotion: () -> Boolean,
    private val onClick: (MomentRow) -> Unit,
) : ListAdapter<MomentRow, MomentsAdapter.Holder>(DIFF) {

    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val loader: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "TimelineThumbs").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val inflight = HashSet<String>()
    private var disposed = false

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.tlMomentThumb)
        val time: TextView = view.findViewById(R.id.tlMomentTime)
        val kind: TextView = view.findViewById(R.id.tlMomentKind)
        val subtitle: TextView = view.findViewById(R.id.tlMomentSubtitle)
        val statusIcon: ImageView = view.findViewById(R.id.tlMomentStatusIcon)
        val status: TextView = view.findViewById(R.id.tlMomentStatus)
        var boundKey: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_moment, parent, false)
        val holder = Holder(v)
        holder.thumb.clipToOutline = true
        v.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
        }
        v.setOnTouchListener { view, ev -> pressFeedback(view, ev); false }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.time.text = row.time
        holder.kind.text = row.kind
        holder.subtitle.text = row.subtitle
        holder.subtitle.visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
        bindStatus(holder, row)
        bindThumb(holder, row)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val row = getItem(position)
        if (payloads.contains(PAYLOAD_STATUS)) bindStatus(holder, row)
        if (payloads.contains(PAYLOAD_THUMB)) bindThumb(holder, row)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.boundKey = null
        holder.thumb.setImageDrawable(null)
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
    }

    fun positionOf(id: String?): Int =
        if (id == null) RecyclerView.NO_POSITION else currentList.indexOfFirst { it.id == id }

    fun dispose() {
        disposed = true
        loader.shutdownNow()
        inflight.clear()
        thumbs.evictAll()
    }

    private fun bindStatus(holder: Holder, row: MomentRow) {
        val ctx = holder.itemView.context
        holder.itemView.isActivated = row.current
        holder.itemView.contentDescription = row.description
        when (MomentText.status(row.severity, row.playing)) {
            MomentText.Status.PLAYING -> {
                val teal = MaterialColors.getColor(holder.itemView, androidx.appcompat.R.attr.colorPrimary)
                holder.statusIcon.setImageResource(R.drawable.tl_ic_playing)
                holder.statusIcon.setColorFilter(teal)
                holder.statusIcon.visibility = View.VISIBLE
                holder.status.setText(R.string.timeline_playing)
                holder.status.setTextColor(teal)
                holder.status.visibility = View.VISIBLE
            }
            MomentText.Status.CRITICAL -> severity(holder, ctx, R.color.overdrive_status_danger, R.string.timeline_critical)
            MomentText.Status.ALERT -> severity(holder, ctx, R.color.overdrive_status_warning, R.string.timeline_alert)
            MomentText.Status.NONE -> {
                holder.statusIcon.visibility = View.GONE
                holder.status.visibility = View.GONE
            }
        }
    }

    private fun severity(holder: Holder, ctx: Context, colorRes: Int, textRes: Int) {
        val color = ContextCompat.getColor(ctx, colorRes)
        holder.statusIcon.setImageResource(R.drawable.tl_status_dot)
        holder.statusIcon.setColorFilter(color)
        holder.statusIcon.visibility = View.VISIBLE
        holder.status.setText(textRes)
        holder.status.setTextColor(color)
        holder.status.visibility = View.VISIBLE
    }

    private fun bindThumb(holder: Holder, row: MomentRow) {
        val key = row.id + "|" + row.fp
        holder.boundKey = key
        val cached = thumbs.get(key)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
            return
        }
        holder.thumb.setImageDrawable(null)
        if (disposed || !inflight.add(key)) return
        val url = row.thumbUrl
        try {
            loader.execute {
                val bmp = try {
                    com.overdrive.app.ui.util.RecordingsApiClient.fetchThumbnail(url)?.let { decode(it) }
                } catch (t: Throwable) {
                    null
                }
                main.post {
                    inflight.remove(key)
                    if (disposed || bmp == null) return@post
                    thumbs.put(key, bmp)
                    val pos = currentList.indexOfFirst { it.id + "|" + it.fp == key }
                    if (pos >= 0) notifyItemChanged(pos, PAYLOAD_THUMB)
                }
            }
        } catch (t: Throwable) {
            inflight.remove(key)
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_TARGET_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun pressFeedback(view: View, ev: MotionEvent) {
        if (reducedMotion() || !ValueAnimator.areAnimatorsEnabled()) return
        val target = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> PRESS_SCALE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
            else -> return
        }
        view.animate().scaleX(target).scaleY(target).setDuration(PRESS_MS).start()
    }

    companion object {
        private const val PAYLOAD_STATUS = "status"
        private const val PAYLOAD_THUMB = "thumb"
        private const val THUMB_CACHE_BYTES = 4 * 1024 * 1024
        private const val THUMB_TARGET_PX = 240
        private const val PRESS_SCALE = 0.97f
        private const val PRESS_MS = 100L

        private val DIFF = object : DiffUtil.ItemCallback<MomentRow>() {
            override fun areItemsTheSame(a: MomentRow, b: MomentRow): Boolean = a.id == b.id

            override fun areContentsTheSame(a: MomentRow, b: MomentRow): Boolean = a == b

            override fun getChangePayload(a: MomentRow, b: MomentRow): Any? =
                if (a.copy(current = b.current, playing = b.playing, description = b.description) == b) {
                    PAYLOAD_STATUS
                } else {
                    null
                }
        }
    }
}
