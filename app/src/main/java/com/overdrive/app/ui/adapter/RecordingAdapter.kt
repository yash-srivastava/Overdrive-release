package com.overdrive.app.ui.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.overdrive.app.ui.model.RecordingFile
import com.overdrive.app.R
import com.overdrive.app.ui.util.RecordingUiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private val thumbnailLock = Any()
    private var disposed = false
    private val thumbnailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Multi-select state
    var selectMode = false
        private set
    private val selectedItems = mutableSetOf<String>() // paths
    private var activeRecordingPath: String? = null

    fun setActiveRecording(path: String?) {
        if (activeRecordingPath == path) return
        val previous = activeRecordingPath
        activeRecordingPath = path
        currentList.indexOfFirst { it.path == previous }
            .takeIf { it >= 0 }
            ?.let(::notifyItemChanged)
        currentList.indexOfFirst { it.path == path }
            .takeIf { it >= 0 }
            ?.let(::notifyItemChanged)
    }
    
    fun enterSelectMode() {
        selectMode = true
        selectedItems.clear()
        rebindAllItems()
    }
    
    fun exitSelectMode() {
        selectMode = false
        selectedItems.clear()
        rebindAllItems()
    }
    
    fun selectAllLoaded() {
        for (i in 0 until itemCount) {
            selectedItems.add(getItem(i).path)
        }
        rebindAllItems()
        onSelectionChanged?.invoke(selectedItems.size)
    }
    
    fun deselectAll() {
        selectedItems.clear()
        rebindAllItems()
        onSelectionChanged?.invoke(0)
    }
    
    fun getSelectedRecordings(): List<RecordingFile> {
        return currentList.filter { it.path in selectedItems }
    }

    fun getSelectedPaths(): Set<String> = selectedItems.toSet()

    fun restoreSelection(selectMode: Boolean, selectedPaths: Collection<String>) {
        this.selectMode = selectMode
        selectedItems.clear()
        if (selectMode) {
            selectedItems.addAll(selectedPaths.filter(String::isNotEmpty))
        }
        rebindAllItems()
        onSelectionChanged?.invoke(selectedItems.size)
    }

    fun retainSelection(availablePaths: Set<String>) {
        if (!selectMode) return
        if (selectedItems.retainAll(availablePaths)) {
            rebindAllItems()
            onSelectionChanged?.invoke(selectedItems.size)
        }
    }

    private fun rebindAllItems() {
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
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

    override fun onViewRecycled(holder: RecordingViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
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
        private var thumbnailJob: Job? = null

        fun bind(recording: RecordingFile) {
            thumbnailJob?.cancel()
            tvCameraId.text = "C${recording.cameraId}"
            tvRecordingTime.text = recording.formattedTime
            if (recording.durationMs > 0) {
                tvDuration.text = recording.formattedDuration
                tvDuration.visibility = View.VISIBLE
            } else if (landscapeRows) {
                tvDuration.text = "--:--"
                tvDuration.visibility = View.VISIBLE
            } else {
                tvDuration.visibility = View.GONE
            }
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
                        sb.tintAsStatusBadge(
                            R.color.overdrive_status_success_container,
                            R.color.overdrive_status_success
                        )
                        sb.visibility = View.VISIBLE
                    }
                    "USB" -> {
                        sb.text = ctx.getString(R.string.recording_lib_storage_usb)
                        sb.tintAsStatusBadge(
                            R.color.overdrive_status_info_container,
                            R.color.overdrive_status_info
                        )
                        sb.visibility = View.VISIBLE
                    }
                    "INTERNAL" -> {
                        sb.text = ctx.getString(R.string.recording_lib_storage_internal)
                        sb.tintAsNeutralBadge()
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
                    tvSeverity?.tintAsStatusBadge(
                        R.color.overdrive_status_danger_container,
                        R.color.overdrive_status_danger
                    )
                    severityStripe?.let { stripe ->
                        stripe.visibility = View.VISIBLE
                        stripe.setBackgroundColor(
                            stripe.resolveColor(R.color.overdrive_status_danger)
                        )
                    }
                }
                "ALERT" -> {
                    tvSeverity?.visibility = View.VISIBLE
                    tvSeverity?.text = "ALERT"
                    tvSeverity?.tintAsStatusBadge(
                        R.color.overdrive_status_warning_container,
                        R.color.overdrive_status_warning
                    )
                    severityStripe?.let { stripe ->
                        stripe.visibility = View.VISIBLE
                        stripe.setBackgroundColor(
                            stripe.resolveColor(R.color.overdrive_status_warning)
                        )
                    }
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
                tvActorSummary?.visibility = View.INVISIBLE
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
                tvLocation?.visibility = View.INVISIBLE
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
                updateSelectionAccessibility(recording, placeText, cbSelect.isChecked)

                cbSelect.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedItems.add(recording.path)
                    else           selectedItems.remove(recording.path)
                    itemView.isActivated = isChecked
                    updateSelectionAccessibility(recording, placeText, isChecked)
                    onSelectionChanged?.invoke(selectedItems.size)
                }

                itemView.setOnClickListener {
                    cbSelect.isChecked = !cbSelect.isChecked
                }
                itemView.isActivated = recording.path in selectedItems

                itemView.setOnLongClickListener(null)
            } else {
                cbSelect.setOnCheckedChangeListener(null)
                cbSelect.visibility = View.GONE
                cbSelect.contentDescription = null
                btnDelete?.visibility = View.VISIBLE
                btnMore?.visibility = View.VISIBLE
                itemView.contentDescription = itemView.context.getString(
                    R.string.recording_item_description,
                    recording.formattedTime,
                    placeText ?: recording.file.name,
                    recording.formattedSize
                )

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
                itemView.isActivated = recording.path == activeRecordingPath
                itemView.setOnLongClickListener {
                    enterSelectMode()
                    selectedItems.add(recording.path)
                    rebindAllItems()
                    onSelectionChanged?.invoke(selectedItems.size)
                    true
                }
            }
        }

        private fun renderActiveState(recording: RecordingFile) {
            val active = !selectMode && recording.path == activeRecordingPath
            activeIndicator?.visibility = if (active) View.VISIBLE else View.GONE
            card?.isSelected = active
        }

        private fun showMoreMenu(anchor: View, recording: RecordingFile) {
            val shareTitle = anchor.context.getString(R.string.action_share)
            val deleteTitle = anchor.context.getString(R.string.action_delete)
            PopupMenu(anchor.context, anchor).apply {
                if (onShare != null) menu.add(shareTitle)
                menu.add(deleteTitle)
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

        fun recycle() {
            thumbnailJob?.cancel()
            thumbnailJob = null
            ivThumbnail.setImageResource(R.color.surface_variant)
        }

        private fun updateSelectionAccessibility(
            recording: RecordingFile,
            placeText: String?,
            selected: Boolean,
        ) {
            val context = itemView.context
            val action = context.getString(
                if (selected) {
                    R.string.recording_item_selected_action
                } else {
                    R.string.recording_item_not_selected_action
                }
            )
            itemView.contentDescription = context.getString(
                R.string.recording_item_selection_description,
                recording.formattedTime,
                placeText ?: recording.file.name,
                recording.formattedSize,
                action,
            )
            cbSelect.contentDescription = context.getString(
                if (selected) {
                    R.string.recording_item_deselect
                } else {
                    R.string.recording_item_select
                },
                recording.file.name,
            )
        }
        
        private fun loadThumbnail(recording: RecordingFile) {
            // Cache key includes the hero presence so we don't mix MP4-frame thumbs
            // with hero AI thumbs in memory.
            val cacheKey = recording.heroThumbnailFile?.absolutePath
                ?: recording.thumbnailUrl
                ?: recording.path

            val cached = thumbnailCache.get(cacheKey)
            if (cached != null) {
                ivThumbnail.setImageBitmap(cached)
                return
            }

            ivThumbnail.setImageResource(R.color.surface_variant)

            thumbnailJob = thumbnailScope.launch {
                // Prefer the hero JPEG written by ThumbnailBuffer next to the MP4.
                // Falls back to MediaMetadataRetriever for legacy clips with no
                // sidecar.
                val thumbnail = recording.heroThumbnailFile?.let { decodeJpeg(it) }
                    ?: recording.file.takeIf { it.canRead() }?.let {
                        extractThumbnail(it.absolutePath)
                    }
                    ?: recording.thumbnailUrl?.let { decodeRemoteThumbnail(it) }
                    ?: return@launch
                val accepted = synchronized(thumbnailLock) {
                    if (disposed || !isActive) {
                        false
                    } else {
                        thumbnailCache.put(cacheKey, thumbnail)
                        true
                    }
                }
                if (!accepted) {
                    thumbnail.recycle()
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION &&
                        getItem(bindingAdapterPosition).path == recording.path) {
                        ivThumbnail.setImageBitmap(thumbnail)
                    }
                }
            }
        }

        private fun decodeJpeg(file: java.io.File): Bitmap? {
            return try {
                decodeScaled(file.absolutePath)
            } catch (e: Exception) {
                null
            }
        }

        private suspend fun decodeRemoteThumbnail(url: String): Bitmap? {
            val first = com.overdrive.app.ui.util.RecordingsApiClient.fetchThumbnail(url)
            val bytes = first ?: run {
                delay(1_200L)
                com.overdrive.app.ui.util.RecordingsApiClient.fetchThumbnail(url)
            } ?: return null
            return decodeScaled(bytes)
        }

        private fun decodeScaled(path: String): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            return BitmapFactory.decodeFile(path, options)
        }

        private fun decodeScaled(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }

        private fun sampleSize(width: Int, height: Int): Int {
            var sample = 1
            while (width / (sample * 2) >= THUMBNAIL_WIDTH_PX &&
                height / (sample * 2) >= THUMBNAIL_HEIGHT_PX
            ) {
                sample *= 2
            }
            return sample
        }

        private fun extractThumbnail(path: String): Bitmap? {
            var retriever: MediaMetadataRetriever? = null
            return try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                retriever.getScaledFrameAtTime(
                    1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMBNAIL_WIDTH_PX,
                    THUMBNAIL_HEIGHT_PX,
                )
            } catch (e: Exception) {
                null
            } finally {
                try {
                    retriever?.release()
                } catch (_: Exception) {
                    // The retriever may already be invalid after a decode error.
                }
            }
        }
    }
    
    fun clearCache() {
        thumbnailCache.evictAll()
    }

    fun dispose() {
        synchronized(thumbnailLock) {
            disposed = true
            thumbnailScope.cancel()
            thumbnailCache.evictAll()
        }
    }
    
    private class RecordingDiffCallback : DiffUtil.ItemCallback<RecordingFile>() {
        override fun areItemsTheSame(oldItem: RecordingFile, newItem: RecordingFile): Boolean {
            return oldItem.path == newItem.path
        }
        
        override fun areContentsTheSame(oldItem: RecordingFile, newItem: RecordingFile): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val THUMBNAIL_WIDTH_PX = 480
        private const val THUMBNAIL_HEIGHT_PX = 270
    }
}

private fun View.resolveColor(@ColorRes colorRes: Int): Int =
    ContextCompat.getColor(context, colorRes)

/**
 * Paint a badge in a status tone. The tonal container carries the fill and the
 * matching status colour the label, which keeps the pair legible in both themes
 * and over a thumbnail — the container tones are ~80% opaque.
 */
private fun TextView.tintAsStatusBadge(@ColorRes fill: Int, @ColorRes label: Int) {
    setBackgroundColor(resolveColor(fill))
    setTextColor(resolveColor(label))
}

/**
 * The same treatment for a badge that carries no status meaning. Surface roles
 * live under material's attr namespace here, not the app's (nonTransitiveRClass).
 */
private fun TextView.tintAsNeutralBadge() {
    setBackgroundColor(
        MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHighest
        )
    )
    setTextColor(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    )
}
