package com.overdrive.app.ui.adapter

import android.content.res.ColorStateList
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
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.overdrive.app.ui.model.RecordingFile
import com.overdrive.app.R
import com.overdrive.app.ui.util.RecordingUiText
import com.overdrive.app.ui.util.SafeKeepClipClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Adapter for displaying recording files with video thumbnails.
 * Supports multi-select mode for batch operations.
 */
class RecordingAdapter(
    private val onPlay: (RecordingFile) -> Unit,
    private val onDelete: (RecordingFile) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null,
    private val onShare: ((RecordingFile) -> Unit)? = null,
    private val onEvidencePack: ((RecordingFile) -> Unit)? = null,
    private val landscapeRows: Boolean = false,
    private val onSafeKeepAction: ((RecordingFile, SafeKeepAction) -> Unit)? = null
) : ListAdapter<RecordingFile, RecordingAdapter.RecordingViewHolder>(RecordingDiffCallback()) {

    enum class SafeKeepAction { UPLOAD_NOW, LOCK, UNLOCK, OPEN_CLOUD }

    // SafeKeep pill state keyed by file name. Empty, with every pill hidden, while SafeKeep is off.
    var safeKeepEnabled = false
        private set
    private var safeKeepStates: Map<String, SafeKeepClipClient.ClipState> = emptyMap()

    /** Rebinds only the pills whose state changed (payload bind, no thumbnail reload). */
    fun setSafeKeepStates(enabled: Boolean, states: Map<String, SafeKeepClipClient.ClipState>) {
        val flipped = enabled != safeKeepEnabled
        val previous = safeKeepStates
        safeKeepEnabled = enabled
        safeKeepStates = if (enabled) states else emptyMap()
        for (i in 0 until itemCount) {
            val name = getItem(i).file.name
            if (flipped || previous[name] != safeKeepStates[name]) {
                notifyItemChanged(i, PAYLOAD_SAFEKEEP)
            }
        }
    }

    fun safeKeepState(name: String): SafeKeepClipClient.ClipState? =
        if (safeKeepEnabled) safeKeepStates[name] else null

    /** When set, a row's ⋮ goes through the host, which may refresh SafeKeep state before calling show. */
    var actionsMenuGate: ((anchor: View, recording: RecordingFile, show: () -> Unit) -> Unit)? = null
    
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
    // At most two thumbnail decodes/fetches at once; Dispatchers.IO alone would start one per visible row.
    private val thumbnailSlots = Semaphore(2)
    
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

    /**
     * Per-recording actions menu anchored to [anchor]. Share and delete are left out when the
     * caller already shows them as buttons.
     */
    fun showActionsMenu(
        anchor: View,
        recording: RecordingFile,
        includeDirectActions: Boolean = true,
        includeDelete: Boolean = includeDirectActions,
    ) {
        val shareTitle = anchor.context.getString(R.string.action_share)
        val evidenceTitle = anchor.context.getString(
            R.string.action_create_evidence_pack
        )
        val deleteTitle = anchor.context.getString(R.string.action_delete)
        // SafeKeep entries only while it is on; state comes from the last pill refresh.
        val skOn = onSafeKeepAction != null && safeKeepEnabled
        val sk = if (skOn) safeKeepState(recording.file.name) else null
        val skLocked = sk?.locked == true
        val uploadTitle = anchor.context.getString(R.string.action_safekeep_upload_now)
        val lockTitle = anchor.context.getString(
            if (skLocked) R.string.action_safekeep_unlock else R.string.action_safekeep_lock
        )
        val cloudTitle = anchor.context.getString(R.string.action_safekeep_open_cloud)
        PopupMenu(anchor.context, anchor).apply {
            if (includeDirectActions && onShare != null) menu.add(shareTitle)
            if (onEvidencePack != null && !recording.recordingId.isNullOrEmpty()) {
                menu.add(evidenceTitle)
            }
            if (skOn) {
                if (sk?.link != null) menu.add(cloudTitle)
                if (sk?.state != "saved" && sk?.state != "uploading") menu.add(uploadTitle)
                menu.add(lockTitle)
            }
            if (includeDelete) menu.add(deleteTitle)
            if (menu.size() == 0) return
            setOnMenuItemClickListener { item ->
                when (item.title?.toString()) {
                    cloudTitle -> {
                        onSafeKeepAction?.invoke(recording, SafeKeepAction.OPEN_CLOUD)
                        true
                    }
                    uploadTitle -> {
                        onSafeKeepAction?.invoke(recording, SafeKeepAction.UPLOAD_NOW)
                        true
                    }
                    lockTitle -> {
                        onSafeKeepAction?.invoke(
                            recording,
                            if (skLocked) SafeKeepAction.UNLOCK else SafeKeepAction.LOCK
                        )
                        true
                    }
                    shareTitle -> {
                        onShare?.invoke(recording)
                        true
                    }
                    evidenceTitle -> {
                        onEvidencePack?.invoke(recording)
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

    override fun onBindViewHolder(
        holder: RecordingViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && payloads.all { it === PAYLOAD_SAFEKEEP }) {
            val recording = getItem(position)
            holder.bindSafeKeep(recording)
            holder.renderOverflow(recording)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
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
        private val tvSafeKeepBadge: TextView? = itemView.findViewById(R.id.tvSafeKeepBadge)
        private var thumbnailJob: Job? = null
        // Share and delete sit on the row; the overflow then carries only the extra actions.

        fun bind(recording: RecordingFile) {
            val ctx = itemView.context
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

            // Type badge — short prefix label for the on-disk filename group (cam_/event_/dvr_/proximity_).
            // Shown on every row; on a line too narrow for the whole label it collapses rather than read "…".
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
                badge.tintAsTypeBadge()
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
            bindSafeKeep(recording)

            // Severity badge + stripe (item 7) — only when v3 sidecar provided severity
            when (recording.peakSeverity?.uppercase()) {
                "CRITICAL" -> {
                    tvSeverity?.visibility = View.VISIBLE
                    tvSeverity?.text = ctx.getString(R.string.recording_lib_chip_critical)
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
                    tvSeverity?.text = ctx.getString(R.string.recording_lib_chip_alert)
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

            // Actor + proximity summary (v3 only); rows with a headline slot show the
            // headline on the detail line instead when there is no summary.
            val actorSummary = RecordingUiText.actorAndDistance(itemView.context, recording)
            if (actorSummary != null) {
                tvActorSummary?.visibility = View.VISIBLE
                tvActorSummary?.text = actorSummary
                tvEventTitle?.visibility = View.GONE
            } else {
                tvActorSummary?.visibility =
                    if (tvEventTitle != null) View.GONE else View.INVISIBLE
                tvEventTitle?.visibility = View.VISIBLE
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
                btnDelete?.visibility = View.GONE
                itemView.contentDescription = itemView.context.getString(
                    R.string.recording_item_description,
                    recording.formattedTime,
                    placeText ?: recording.file.name,
                    recording.formattedSize
                )

                btnDelete?.setOnClickListener { onDelete(recording) }
                // Share is wired only when the host opted into the share callback.
                if (onShare != null) {
                    btnShare?.visibility = View.VISIBLE
                    btnShare?.setOnClickListener { onShare.invoke(recording) }
                } else {
                    btnShare?.visibility = View.GONE
                    btnShare?.setOnClickListener(null)
                }
                renderOverflow(recording)
                btnMore?.setOnClickListener { anchor ->
                    val show = {
                        // A delayed menu opens only if this row still shows the same clip.
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION && getItem(pos).path == recording.path) {
                            showActionsMenu(
                                anchor, recording,
                                includeDirectActions = btnShare?.visibility != View.VISIBLE,
                                includeDelete = true,
                            )
                        }
                    }
                    actionsMenuGate?.invoke(anchor, recording, show) ?: show()
                }
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

        /** SafeKeep pill: cloud glyph (lock when locked) in the state's tone; saved shows the glyph alone. */
        fun bindSafeKeep(recording: RecordingFile) {
            val badge = tvSafeKeepBadge ?: return
            val st = safeKeepState(recording.file.name)
            val ctx = badge.context
            val text = when (st?.state) {
                "saved" -> ctx.getString(R.string.recording_safekeep_saved)
                "uploading" -> ctx.getString(R.string.recording_safekeep_uploading, st.progress)
                "queued" -> ctx.getString(R.string.recording_safekeep_queued)
                "waiting" -> ctx.getString(R.string.recording_safekeep_waiting)
                "failed" -> ctx.getString(R.string.recording_safekeep_failed)
                else -> if (st?.locked == true) ctx.getString(R.string.recording_safekeep_locked) else null
            }
            if (st == null || text == null) {
                badge.visibility = View.GONE
                badge.setCompoundDrawablesRelative(null, null, null, null)
                badge.contentDescription = null
                return
            }
            when (st.state) {
                "saved" -> badge.tintAsStatusBadge(
                    R.color.overdrive_status_success_container, R.color.overdrive_status_success)
                "uploading" -> badge.tintAsStatusBadge(
                    R.color.overdrive_status_info_container, R.color.overdrive_status_info)
                "queued", "waiting" -> badge.tintAsStatusBadge(
                    R.color.overdrive_status_warning_container, R.color.overdrive_status_warning)
                "failed" -> badge.tintAsStatusBadge(
                    R.color.overdrive_status_danger_container, R.color.overdrive_status_danger)
                else -> badge.tintAsNeutralBadge()
            }
            // Thumbnail-sized pill: the glyph carries "SafeKeep", uploading shows just the progress.
            val glyphOnly = st.state == "saved" && !st.locked
            badge.text = when {
                glyphOnly -> ""
                st.state == "uploading" ->
                    java.text.NumberFormat.getPercentInstance().format(st.progress / 100.0)
                else -> text
            }
            val glyph = ContextCompat.getDrawable(
                ctx,
                if (st.locked) R.drawable.ic_security_lock else R.drawable.ic_cloud
            )?.mutate()?.also {
                val px = (badge.textSize * 1.3f).toInt()
                it.setBounds(0, 0, px, px)
                it.setTint(badge.currentTextColor)
            }
            badge.setCompoundDrawablesRelative(glyph, null, null, null)
            badge.compoundDrawablePadding =
                if (glyphOnly) 0 else (3 * badge.resources.displayMetrics.density).toInt()
            badge.contentDescription = ctx.getString(R.string.cd_recording_safekeep_state, text)
            badge.visibility = View.VISIBLE
        }

        /** The ⋮ menu holds Delete plus the evidence and SafeKeep actions on every row. */
        fun renderOverflow(recording: RecordingFile) {
            val more = btnMore ?: return
            // Delete and the clip actions always live here, in both orientations.
            more.visibility = if (selectMode) View.GONE else View.VISIBLE
        }

        private fun renderActiveState(recording: RecordingFile) {
            val active = !selectMode && recording.path == activeRecordingPath
            activeIndicator?.visibility = if (active) View.VISIBLE else View.GONE
            card?.isSelected = active
            card?.setCardBackgroundColor(
                if (active) {
                    MaterialColors.getColor(
                        itemView,
                        com.google.android.material.R.attr.colorSurfaceContainerHigh
                    )
                } else {
                    Color.TRANSPARENT
                }
            )
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
                val thumbnail = recording.heroThumbnailFile?.let { thumbnailSlots.withPermit { decodeJpeg(it) } }
                    ?: recording.file.takeIf { it.canRead() }?.let {
                        thumbnailSlots.withPermit { extractThumbnail(it.absolutePath) }
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

        // Each attempt takes its own slot so the retry wait never holds one.
        private suspend fun decodeRemoteThumbnail(url: String): Bitmap? {
            val first = thumbnailSlots.withPermit {
                com.overdrive.app.ui.util.RecordingsApiClient.fetchThumbnail(url)
            }
            val bytes = first ?: run {
                delay(1_200L)
                thumbnailSlots.withPermit {
                    com.overdrive.app.ui.util.RecordingsApiClient.fetchThumbnail(url)
                }
            } ?: return null
            return thumbnailSlots.withPermit { decodeScaled(bytes) }
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
                )?.let { com.overdrive.app.surveillance.LensTag.dewarpFrameForClip(it, java.io.File(path)) }
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
        private val PAYLOAD_SAFEKEEP = Any()
    }
}

private fun View.resolveColor(@ColorRes colorRes: Int): Int =
    ContextCompat.getColor(context, colorRes)

/**
 * Paint a badge in a status tone: the tonal container carries the fill and the
 * matching status colour the label.
 */
private fun TextView.tintAsStatusBadge(@ColorRes fill: Int, @ColorRes label: Int) {
    paintBadge(resolveColor(fill), resolveColor(label))
}

/**
 * The same treatment for a badge that carries no status meaning. Surface roles
 * resolve against material's attr namespace, not the app's (nonTransitiveRClass).
 */
private fun TextView.tintAsNeutralBadge() {
    paintBadge(
        MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHighest
        ),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    )
}

/** Recording type tag on a faint primary fill; on-primary-container keeps 4.5:1 in both themes. */
private fun TextView.tintAsTypeBadge() {
    val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    paintBadge(
        ColorUtils.setAlphaComponent(primary, TYPE_BADGE_FILL_ALPHA),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer)
    )
}

/** Tints a shaped background so its corners survive; falls back to a flat fill. */
private fun TextView.paintBadge(@ColorInt fill: Int, @ColorInt label: Int) {
    if (background != null) {
        backgroundTintList = ColorStateList.valueOf(fill)
    } else {
        setBackgroundColor(fill)
    }
    setTextColor(label)
}

private const val TYPE_BADGE_FILL_ALPHA = 0x24
