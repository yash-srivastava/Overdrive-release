package com.overdrive.app.ui.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.overdrive.app.ui.model.RecordingFile
import com.overdrive.app.R
import com.overdrive.app.ui.util.RecordingUiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter for displaying recording files with video thumbnails.
 * Supports multi-select mode for batch operations.
 */
class RecordingAdapter(
    private val onPlay: (RecordingFile) -> Unit,
    private val onDelete: (RecordingFile) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null,
    private val onShare: ((RecordingFile) -> Unit)? = null,
    private val landscapeRows: Boolean = false
) : ListAdapter<RecordingFile, RecordingAdapter.RecordingViewHolder>(RecordingDiffCallback()) {
    
    // Cache for thumbnails — size-bounded LRU. The cap is set in BYTES so a
    // mix of small sidecar JPEGs (~50 KB decoded) and full-res
    // MediaMetadataRetriever frames (~1 MB on a 1080p clip) coexist without
    // unbounded growth as the user scrolls through hundreds of recordings.
    // 8 MB ≈ 100 sidecar thumbs OR ~16 full-res frames, comfortable for a 4 GB
    // RAM head unit.
    private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    
    // Multi-select state
    var selectMode = false
        private set
    private val selectedItems = mutableSetOf<String>() // paths
    private var activeRecordingPath: String? = null

    fun setActiveRecording(path: String?) {
        if (activeRecordingPath == path) return
        val oldPath = activeRecordingPath
        activeRecordingPath = path
        currentList.indexOfFirst { it.path == oldPath }
            .takeIf { it >= 0 }
            ?.let(::notifyItemChanged)
        currentList.indexOfFirst { it.path == path }
            .takeIf { it >= 0 }
            ?.let(::notifyItemChanged)
    }
    
    fun enterSelectMode() {
        selectMode = true
        selectedItems.clear()
        notifyDataSetChanged()
    }
    
    fun exitSelectMode() {
        selectMode = false
        selectedItems.clear()
        notifyDataSetChanged()
    }
    
    fun selectAll() {
        for (i in 0 until itemCount) {
            selectedItems.add(getItem(i).path)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItems.size)
    }
    
    fun deselectAll() {
        selectedItems.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }
    
    fun getSelectedRecordings(): List<RecordingFile> {
        return currentList.filter { it.path in selectedItems }
    }
    
    val selectedCount: Int get() = selectedItems.size
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val layout = if (landscapeRows) {
            R.layout.item_recording_landscape
        } else {
            R.layout.item_recording
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(layout, parent, false)
        return RecordingViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class RecordingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView? = itemView as? MaterialCardView
        private val activeIndicator: View? = itemView.findViewById(R.id.activeIndicator)
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvCameraId: TextView = itemView.findViewById(R.id.tvCameraId)
        private val tvRecordingTime: TextView = itemView.findViewById(R.id.tvRecordingTime)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val btnDelete: ImageButton? = itemView.findViewById(R.id.btnDelete)
        private val btnShare: ImageButton? = itemView.findViewById(R.id.btnShare)
        private val btnMore: ImageButton? = itemView.findViewById(R.id.btnMore)
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        private val tvFilename: TextView? = itemView.findViewById(R.id.tvFilename)
        private val tvEventTitle: TextView? = itemView.findViewById(R.id.tvEventTitle)
        private val tvSeverity: TextView? = itemView.findViewById(R.id.tvSeverity)
        private val tvTypeBadge: TextView? = itemView.findViewById(R.id.tvTypeBadge)
        private val tvStorageBadge: TextView? = itemView.findViewById(R.id.tvStorageBadge)
        private val tvActorSummary: TextView? = itemView.findViewById(R.id.tvActorSummary)
        private val severityStripe: View? = itemView.findViewById(R.id.severityStripe)
        private val tvLocation: TextView? = itemView.findViewById(R.id.tvLocation)

        fun bind(recording: RecordingFile) {
            tvCameraId.text = "C${recording.cameraId}"
            tvRecordingTime.text = recording.formattedTime
            tvDuration.text = if (recording.durationMs > 0) recording.formattedDuration else "--:--"
            tvSize.text = recording.formattedSize
            tvFilename?.text = recording.file.name
            tvEventTitle?.text = RecordingUiText.headline(itemView.context, recording)
            renderActiveState(recording)

            // Type badge — short prefix label corresponding to the on-disk
            // filename group. Always visible so the user can map a tile back
            // to its file group at a glance (cam_/event_/dvr_/proximity_).
            tvTypeBadge?.let { badge ->
                val ctx = badge.context
                badge.text = when (recording.type) {
                    RecordingFile.RecordingType.NORMAL ->
                        ctx.getString(R.string.recording_lib_type_normal)
                    RecordingFile.RecordingType.SENTRY ->
                        ctx.getString(R.string.recording_lib_type_event)
                    RecordingFile.RecordingType.PROXIMITY ->
                        ctx.getString(R.string.recording_lib_type_proximity)
                    RecordingFile.RecordingType.OEM_DASHCAM ->
                        ctx.getString(R.string.recording_lib_type_oem)
                    RecordingFile.RecordingType.REPLAY ->
                        ctx.getString(R.string.recording_lib_type_replay)
                }
                badge.visibility = View.VISIBLE
            }

            // Storage tag — where the clip ACTUALLY landed (INTERNAL / SD_CARD /
            // USB). Surfaces the silent SD→internal fallback at the file level
            // (the SD card is bridged behind the USB power rail, so cutting USB
            // power unmounts it and clips fall back to internal). SD/USB use the
            // accent tints (the externals the user intended); INTERNAL stays a
            // neutral dark scrim so a fell-back clip stands out against the
            // green/blue ones. Hidden when the type couldn't be classified.
            tvStorageBadge?.let { sb ->
                val ctx = sb.context
                when (recording.storageType?.uppercase()) {
                    "SD_CARD" -> {
                        sb.text = ctx.getString(R.string.recording_lib_storage_sd)
                        sb.setBackgroundColor(0xCC10B981.toInt())
                        sb.visibility = View.VISIBLE
                    }
                    "USB" -> {
                        sb.text = ctx.getString(R.string.recording_lib_storage_usb)
                        sb.setBackgroundColor(0xCC3B82F6.toInt())
                        sb.visibility = View.VISIBLE
                    }
                    "INTERNAL" -> {
                        sb.text = ctx.getString(R.string.recording_lib_storage_internal)
                        sb.setBackgroundColor(0x99000000.toInt())
                        sb.visibility = View.VISIBLE
                    }
                    else -> sb.visibility = View.GONE
                }
            }

            // Severity badge + stripe (item 7) — only when v3 sidecar provided severity
            when (recording.peakSeverity?.uppercase()) {
                "CRITICAL" -> {
                    tvSeverity?.visibility = View.VISIBLE
                    tvSeverity?.text = "CRITICAL"
                    tvSeverity?.setBackgroundColor(0xCCEF4444.toInt())
                    severityStripe?.visibility = View.VISIBLE
                    severityStripe?.setBackgroundColor(0xFFEF4444.toInt())
                }
                "ALERT" -> {
                    tvSeverity?.visibility = View.VISIBLE
                    tvSeverity?.text = "ALERT"
                    tvSeverity?.setBackgroundColor(0xCCFF8800.toInt())
                    severityStripe?.visibility = View.VISIBLE
                    severityStripe?.setBackgroundColor(0xFFFF9B3D.toInt())
                }
                else -> {
                    tvSeverity?.visibility = View.GONE
                    severityStripe?.visibility = View.GONE
                }
            }

            // Actor + proximity summary (v3 only). Mid-dot prefix reads cleanly
            // without leaning on emoji glyphs that don't render at SOTA quality
            // on the head-unit font stack.
            val actorSummary = RecordingUiText.actorAndDistance(itemView.context, recording)
            if (actorSummary != null) {
                tvActorSummary?.visibility = View.VISIBLE
                tvActorSummary?.text = actorSummary
            } else {
                tvActorSummary?.visibility = View.GONE
            }

            // Place chip — geocoded location from the v3 sidecar's geo.place
            // block. Prefers mediumLabel ("Cheras, Kuala Lumpur") so the row
            // tells the user something meaningful at a glance; falls back to
            // the short label for tight grids and is hidden entirely when no
            // place was resolved.
            val placeText = recording.placeMediumLabel ?: recording.placeShortLabel
            if (!placeText.isNullOrEmpty()) {
                tvLocation?.visibility = View.VISIBLE
                tvLocation?.text = placeText
            } else {
                tvLocation?.visibility = View.GONE
            }

            // Load thumbnail
            loadThumbnail(recording)
            
            if (selectMode) {
                cbSelect.visibility = View.VISIBLE
                cbSelect.setOnCheckedChangeListener(null)
                cbSelect.isChecked = recording.path in selectedItems
                btnDelete?.visibility = View.GONE
                btnShare?.visibility = View.GONE
                btnMore?.visibility = View.GONE

                cbSelect.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedItems.add(recording.path)
                    else           selectedItems.remove(recording.path)
                    onSelectionChanged?.invoke(selectedItems.size)
                }

                itemView.setOnClickListener {
                    cbSelect.isChecked = !cbSelect.isChecked
                }

                itemView.setOnLongClickListener(null)
            } else {
                cbSelect.setOnCheckedChangeListener(null)
                cbSelect.visibility = View.GONE
                btnMore?.visibility = View.VISIBLE

                btnDelete?.visibility = View.VISIBLE
                btnDelete?.setOnClickListener { onDelete(recording) }
                // Per-tile share — only wired when the host fragment opted
                // into the share callback. Hidden otherwise so the tile
                // footer doesn't show a dead button.
                if (onShare != null) {
                    btnShare?.visibility = View.VISIBLE
                    btnShare?.setOnClickListener { onShare.invoke(recording) }
                } else {
                    btnShare?.visibility = View.GONE
                    btnShare?.setOnClickListener(null)
                }
                btnMore?.setOnClickListener { showMoreMenu(it, recording) }
                // Tile body opens the player. Long-press = enter multi-select.
                itemView.setOnClickListener {
                    setActiveRecording(recording.path)
                    onPlay(recording)
                }
                itemView.setOnLongClickListener {
                    enterSelectMode()
                    selectedItems.add(recording.path)
                    notifyDataSetChanged()
                    onSelectionChanged?.invoke(selectedItems.size)
                    true
                }
            }
        }

        private fun renderActiveState(recording: RecordingFile) {
            val active = !selectMode && recording.path == activeRecordingPath
            activeIndicator?.visibility = if (active) View.VISIBLE else View.GONE
            card?.let { recordingCard ->
                // Keep selection styling resource-driven. The previous implementation
                // performed a strict MaterialColors lookup of android:colorAccent on
                // the first tap. Theme.Material3 does not guarantee that legacy
                // framework attribute, so the lookup could throw and take down the
                // whole app before the player was even mounted.
                recordingCard.isSelected = active
                val density = recordingCard.resources.displayMetrics.density
                recordingCard.strokeWidth = ((if (active) 2 else 1) * density).toInt()
            }
        }

        private fun showMoreMenu(anchor: View, recording: RecordingFile) {
            val shareTitle = anchor.context.getString(R.string.action_share)
            val deleteTitle = anchor.context.getString(R.string.action_delete)
            PopupMenu(anchor.context, anchor).apply {
                if (onShare != null) menu.add(shareTitle).setIcon(R.drawable.ic_share)
                menu.add(deleteTitle).setIcon(R.drawable.ic_delete)
                setOnMenuItemClickListener { item ->
                    when (item.title?.toString()) {
                        shareTitle -> {
                            onShare?.invoke(recording)
                            true
                        }
                        deleteTitle -> {
                            onDelete(recording)
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }
        
        private fun loadThumbnail(recording: RecordingFile) {
            // Cache key includes the hero presence so we don't mix MP4-frame thumbs
            // with hero AI thumbs in memory.
            val cacheKey = recording.heroThumbnailFile?.absolutePath
                ?: recording.path

            val cached = thumbnailCache.get(cacheKey)
            if (cached != null) {
                ivThumbnail.setImageBitmap(cached)
                return
            }

            ivThumbnail.setImageResource(R.color.surface_variant)

            CoroutineScope(Dispatchers.IO).launch {
                // Prefer the hero JPEG written by ThumbnailBuffer next to the MP4.
                // Falls back to MediaMetadataRetriever for legacy clips with no
                // sidecar.
                val thumbnail = recording.heroThumbnailFile?.let { decodeJpeg(it) }
                    ?: extractThumbnail(recording.path)
                if (thumbnail != null) {
                    thumbnailCache.put(cacheKey, thumbnail)
                }

                withContext(Dispatchers.Main) {
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                        getItem(bindingAdapterPosition).path == recording.path &&
                        thumbnail != null) {
                        ivThumbnail.setImageBitmap(thumbnail)
                    }
                }
            }
        }

        private fun decodeJpeg(file: java.io.File): Bitmap? {
            return try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                null
            }
        }

        private fun extractThumbnail(path: String): Bitmap? {
            return try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val frame = retriever.getFrameAtTime(1_000_000) // 1 second in
                retriever.release()
                frame
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun clearCache() {
        thumbnailCache.evictAll()
    }
    
    private class RecordingDiffCallback : DiffUtil.ItemCallback<RecordingFile>() {
        override fun areItemsTheSame(oldItem: RecordingFile, newItem: RecordingFile): Boolean {
            return oldItem.path == newItem.path
        }
        
        override fun areContentsTheSame(oldItem: RecordingFile, newItem: RecordingFile): Boolean {
            return oldItem == newItem
        }
    }
}
