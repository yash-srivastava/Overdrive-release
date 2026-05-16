package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.overdrive.app.ui.viewmodel.RecordingViewModel
import com.overdrive.app.client.CameraDaemonClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.overdrive.app.R
import java.util.concurrent.Executors

/**
 * Fragment for recording controls (mode selection, quality settings).
 * Uses Apply button pattern - settings are only sent when Apply is clicked.
 * Manual start/stop recording removed - recording is managed by modes.
 */
class RecordingControlsFragment : Fragment() {
    
    private val recordingViewModel: RecordingViewModel by activityViewModels()
    
    private lateinit var tvStorageUsed: TextView
    private lateinit var tvStorageLimit: TextView
    private lateinit var storageBarFill: View
    
    // Recording mode
    private lateinit var radioGroupRecordingMode: android.widget.RadioGroup
    private lateinit var radioModeNone: android.widget.RadioButton
    private lateinit var radioModeContinuous: android.widget.RadioButton
    private lateinit var radioModeDriveMode: android.widget.RadioButton
    private lateinit var radioModeProximityGuard: android.widget.RadioButton
    private lateinit var cardProximitySettings: com.google.android.material.card.MaterialCardView
    
    // Proximity guard settings
    private lateinit var spinnerTriggerLevel: android.widget.Spinner
    private lateinit var sliderPreRecord: com.google.android.material.slider.Slider
    private lateinit var sliderPostRecord: com.google.android.material.slider.Slider
    private lateinit var tvPreRecordValue: TextView
    private lateinit var tvPostRecordValue: TextView
    
    // Quality settings
    private lateinit var toggleRecordingQuality: MaterialButtonToggleGroup
    private lateinit var toggleRecordingBitrate: MaterialButtonToggleGroup
    private lateinit var toggleVideoCodec: MaterialButtonToggleGroup
    private lateinit var btnApplyQuality: MaterialButton
    
    // Storage limit settings
    private lateinit var sliderRecordingsLimit: com.google.android.material.slider.Slider
    private lateinit var tvRecordingsLimitValue: TextView
    
    // Storage location selection
    private lateinit var toggleRecordingsStorage: MaterialButtonToggleGroup
    private var sdCardStatusLayout: View? = null
    private var sdCardStatusDot: View? = null
    private var tvSdCardStatus: TextView? = null
    private var tvStoragePathInfo: TextView? = null
    private var tvStorageMaxLabel: TextView? = null
    
    // Storage type state
    private var currentStorageType = "INTERNAL"
    private var sdCardAvailable = false
    private var sdCardPath: String? = null
    
    // CDR Cleanup views
    private var cardCdrCleanup: com.google.android.material.card.MaterialCardView? = null
    private var switchCdrCleanup: com.google.android.material.switchmaterial.SwitchMaterial? = null
    private var tvCdrCleanupBadge: TextView? = null
    private var tvCdrUsage: TextView? = null
    private var tvCdrFileCount: TextView? = null
    private var tvCdrDeletable: TextView? = null
    private var sliderCdrReserved: com.google.android.material.slider.Slider? = null
    private var tvCdrReservedValue: TextView? = null
    private var sliderCdrProtected: com.google.android.material.slider.Slider? = null
    private var tvCdrProtectedValue: TextView? = null
    private var btnCdrCleanupNow: MaterialButton? = null
    
    // Daemon client for sending settings to daemon
    private var daemonClient: CameraDaemonClient? = null
    private val executor = Executors.newSingleThreadExecutor()
    
    // Track pending changes (not yet applied)
    private var pendingQuality: String? = null
    private var pendingBitrate: String? = null
    private var pendingCodec: String? = null
    private var pendingStorageLimit: Long? = null
    private var pendingRecordingMode: String? = null
    private var pendingTriggerLevel: String? = null
    private var pendingPreRecord: Int? = null
    private var pendingPostRecord: Int? = null
    private var pendingStorageType: String? = null
    private var pendingCdrEnabled: Boolean? = null
    private var pendingCdrReservedMb: Long? = null
    private var pendingCdrProtectedHours: Int? = null
    private var hasUnsavedChanges = false
    
    // Flag to prevent listener callbacks during initialization
    private var isInitializing = false
    
    // Saved values (currently applied)
    private var savedQuality: String = "NORMAL"
    private var savedBitrate: String = "MEDIUM"
    private var savedCodec: String = "H264"
    private var savedStorageLimit: Long = 500
    private var savedRecordingMode: String = "NONE"
    private var savedTriggerLevel: String = "RED"
    private var savedPreRecord: Int = 5
    private var savedPostRecord: Int = 10
    private var savedStorageType: String = "INTERNAL"
    private var savedCdrEnabled: Boolean = false
    private var savedCdrReservedMb: Long = 2000
    private var savedCdrProtectedHours: Int = 24
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_recording_controls, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupClickListeners()
        observeViewModel()
    }
    
    private fun initViews(view: View) {
        // Manual recording buttons removed - recording is managed by modes
        tvStorageUsed = view.findViewById(R.id.tvStorageUsed)
        tvStorageLimit = view.findViewById(R.id.tvStorageLimit)
        storageBarFill = view.findViewById(R.id.storageBarFill)
        
        // Recording mode
        radioGroupRecordingMode = view.findViewById(R.id.radioGroupRecordingMode)
        radioModeNone = view.findViewById(R.id.radioModeNone)
        radioModeContinuous = view.findViewById(R.id.radioModeContinuous)
        radioModeDriveMode = view.findViewById(R.id.radioModeDriveMode)
        radioModeProximityGuard = view.findViewById(R.id.radioModeProximityGuard)
        cardProximitySettings = view.findViewById(R.id.cardProximitySettings)
        
        // Proximity guard settings
        spinnerTriggerLevel = view.findViewById(R.id.spinnerTriggerLevel)
        sliderPreRecord = view.findViewById(R.id.sliderPreRecord)
        sliderPostRecord = view.findViewById(R.id.sliderPostRecord)
        tvPreRecordValue = view.findViewById(R.id.tvPreRecordValue)
        tvPostRecordValue = view.findViewById(R.id.tvPostRecordValue)
        
        // Setup trigger level spinner
        val triggerLevels = arrayOf("🔴 Close Only (0-0.5m)", "🟡 Medium + Close (0-0.8m)")
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, triggerLevels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTriggerLevel.adapter = adapter
        
        // Quality settings
        toggleRecordingQuality = view.findViewById(R.id.toggleRecordingQuality)
        toggleRecordingBitrate = view.findViewById(R.id.toggleRecordingBitrate)
        toggleVideoCodec = view.findViewById(R.id.toggleVideoCodec)
        btnApplyQuality = view.findViewById(R.id.btnApplyQuality)
        
        // Storage limit settings
        sliderRecordingsLimit = view.findViewById(R.id.sliderRecordingsLimit)
        tvRecordingsLimitValue = view.findViewById(R.id.tvRecordingsLimitValue)
        
        // Storage location selection
        toggleRecordingsStorage = view.findViewById(R.id.toggleRecordingsStorage)
        sdCardStatusLayout = view.findViewById(R.id.sdCardStatusLayout)
        sdCardStatusDot = view.findViewById(R.id.sdCardStatusDot)
        tvSdCardStatus = view.findViewById(R.id.tvSdCardStatus)
        tvStoragePathInfo = view.findViewById(R.id.tvStoragePathInfo)
        tvStorageMaxLabel = view.findViewById(R.id.tvStorageMaxLabel)
        
        // CRITICAL: Disable Apply button immediately on view init
        btnApplyQuality.isEnabled = false
        
        // CDR Cleanup views
        cardCdrCleanup = view.findViewById(R.id.cardCdrCleanup)
        switchCdrCleanup = view.findViewById(R.id.switchCdrCleanup)
        
        tvCdrCleanupBadge = view.findViewById(R.id.tvCdrCleanupBadge)
        tvCdrUsage = view.findViewById(R.id.tvCdrUsage)
        tvCdrFileCount = view.findViewById(R.id.tvCdrFileCount)
        tvCdrDeletable = view.findViewById(R.id.tvCdrDeletable)
        sliderCdrReserved = view.findViewById(R.id.sliderCdrReserved)
        tvCdrReservedValue = view.findViewById(R.id.tvCdrReservedValue)
        sliderCdrProtected = view.findViewById(R.id.sliderCdrProtected)
        tvCdrProtectedValue = view.findViewById(R.id.tvCdrProtectedValue)
        btnCdrCleanupNow = view.findViewById(R.id.btnCdrCleanupNow)
        
        // Load storage settings
        loadStorageSettings()
    }
    
    private fun setupClickListeners() {
        // Apply button - apply all pending changes
        btnApplyQuality.setOnClickListener {
            applySettings()
        }
        
        // Load initial quality settings FIRST (before adding toggle listeners)
        // This prevents listeners from firing during initial UI setup
        loadCurrentQualitySettings()
        
        // Recording mode radio group
        radioGroupRecordingMode.setOnCheckedChangeListener { _, checkedId ->
            if (!isInitializing) {
                pendingRecordingMode = when (checkedId) {
                    R.id.radioModeNone -> "NONE"
                    R.id.radioModeContinuous -> "CONTINUOUS"
                    R.id.radioModeDriveMode -> "DRIVE_MODE"
                    R.id.radioModeProximityGuard -> "PROXIMITY_GUARD"
                    else -> null
                }
                updateProximitySettingsVisibility()
                markChanged()
            }
        }
        
        // Proximity guard settings listeners
        spinnerTriggerLevel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isInitializing) {
                    pendingTriggerLevel = if (position == 0) "RED" else "YELLOW_RED"
                    markChanged()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        sliderPreRecord.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                pendingPreRecord = value.toInt()
                tvPreRecordValue.text = "${value.toInt()} seconds"
                markChanged()
            }
        }
        
        sliderPostRecord.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                pendingPostRecord = value.toInt()
                tvPostRecordValue.text = "${value.toInt()} seconds"
                markChanged()
            }
        }
        
        // NOW add toggle listeners AFTER initial values are set
        // Recording quality toggle - just mark as changed, don't apply immediately
        toggleRecordingQuality.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isInitializing) {
                pendingQuality = when (checkedId) {
                    R.id.btnRecQualityLow -> "LOW"
                    R.id.btnRecQualityReduced -> "REDUCED"
                    R.id.btnRecQualityNormal -> "NORMAL"
                    else -> null
                }
                markChanged()
            }
        }
        
        // Recording bitrate toggle - just mark as changed, don't apply immediately
        toggleRecordingBitrate.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isInitializing) {
                pendingBitrate = when (checkedId) {
                    R.id.btnBitrateLow -> "LOW"
                    R.id.btnBitrateMedium -> "MEDIUM"
                    R.id.btnBitrateHigh -> "HIGH"
                    else -> null
                }
                markChanged()
            }
        }
        
        // Video codec toggle - just mark as changed, don't apply immediately
        toggleVideoCodec.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isInitializing) {
                pendingCodec = when (checkedId) {
                    R.id.btnCodecH264 -> "H264"
                    R.id.btnCodecH265 -> "H265"
                    else -> null
                }
                markChanged()
            }
        }
        
        // Storage limit slider
        sliderRecordingsLimit.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                pendingStorageLimit = value.toLong()
                tvRecordingsLimitValue.text = "${value.toInt()} MB"
                tvStorageLimit.text = "${value.toInt()} MB limit"
                markChanged()
            }
        }
        
        // Storage type toggle
        toggleRecordingsStorage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isInitializing) {
                val newType = when (checkedId) {
                    R.id.btnStorageInternal -> "INTERNAL"
                    R.id.btnStorageSdCard -> "SD_CARD"
                    else -> "INTERNAL"
                }
                
                // Check if SD card is available when selecting SD card
                if (newType == "SD_CARD" && !sdCardAvailable) {
                    Toast.makeText(context, getString(R.string.toast_sd_card_not_available), Toast.LENGTH_SHORT).show()
                    // Revert to internal
                    toggleRecordingsStorage.check(R.id.btnStorageInternal)
                    return@addOnButtonCheckedListener
                }
                
                pendingStorageType = newType
                currentStorageType = newType
                updateStorageTypeUI()
                updateCdrCleanupVisibility()
                markChanged()
            }
        }
        
        // CDR Cleanup listeners
        setupCdrCleanupListeners()
    }
    
    private fun setupCdrCleanupListeners() {
        switchCdrCleanup?.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                pendingCdrEnabled = isChecked
                tvCdrCleanupBadge?.text = if (isChecked) "ON" else "OFF"
                tvCdrCleanupBadge?.setTextColor(resources.getColor(
                    if (isChecked) R.color.status_running else R.color.text_muted, null))
                markChanged()
            }
        }
        
        sliderCdrReserved?.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                val mb = value.toInt()
                pendingCdrReservedMb = mb.toLong()
                tvCdrReservedValue?.text = if (mb >= 1000) "${mb / 1000} GB" else "$mb MB"
                markChanged()
            }
        }
        
        sliderCdrProtected?.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                pendingCdrProtectedHours = value.toInt()
                tvCdrProtectedValue?.text = "${value.toInt()}h"
                markChanged()
            }
        }
        
        btnCdrCleanupNow?.setOnClickListener {
            triggerCdrCleanup()
        }
    }
    
    private fun updateCdrCleanupVisibility() {
        val showCard = currentStorageType == "SD_CARD" && sdCardAvailable
        cardCdrCleanup?.visibility = if (showCard) View.VISIBLE else View.GONE
        
        if (showCard) {
            loadCdrConfig()
        }
    }
    
    private fun loadCdrConfig() {
        executor.submit {
            try {
                val cleaner = com.overdrive.app.storage.ExternalStorageCleaner.getInstance()
                
                val enabled = cleaner.isEnabled
                val reservedMb = cleaner.reservedSpaceMb
                val protectedHours = cleaner.protectedHours
                val cdrUsage = cleaner.cdrUsage
                val cdrCount = cleaner.cdrFileCount
                val deletableSize = cleaner.deletableSize
                
                activity?.runOnUiThread {
                    isInitializing = true
                    
                    // Save current values
                    savedCdrEnabled = enabled
                    savedCdrReservedMb = reservedMb
                    savedCdrProtectedHours = protectedHours
                    
                    // Initialize pending to saved
                    pendingCdrEnabled = enabled
                    pendingCdrReservedMb = reservedMb
                    pendingCdrProtectedHours = protectedHours
                    
                    switchCdrCleanup?.isChecked = enabled
                    tvCdrCleanupBadge?.text = if (enabled) "ON" else "OFF"
                    tvCdrCleanupBadge?.setTextColor(resources.getColor(
                        if (enabled) R.color.status_running else R.color.text_muted, null))
                    
                    // Snap to nearest valid step (500) to avoid IllegalStateException
                    val snappedReserved = ((reservedMb + 250) / 500 * 500).coerceIn(500, 5000)
                    sliderCdrReserved?.value = snappedReserved.toFloat()
                    tvCdrReservedValue?.text = if (snappedReserved >= 1000) "${snappedReserved / 1000} GB" else "$snappedReserved MB"

                    sliderCdrProtected?.value = protectedHours.toFloat().coerceIn(1f, 72f)
                    tvCdrProtectedValue?.text = "${protectedHours}h"
                    
                    tvCdrUsage?.text = com.overdrive.app.storage.ExternalStorageCleaner.formatSize(cdrUsage)
                    tvCdrFileCount?.text = cdrCount.toString()
                    tvCdrDeletable?.text = com.overdrive.app.storage.ExternalStorageCleaner.formatSize(deletableSize)
                    
                    isInitializing = false
                }
            } catch (e: Exception) {
                android.util.Log.w("RecordingControls", "Failed to load CDR config: ${e.message}")
            }
        }
    }
    
    private fun saveCdrConfig(enabled: Boolean? = null, reservedSpaceMb: Long? = null, protectedHours: Int? = null) {
        executor.submit {
            try {
                val cleaner = com.overdrive.app.storage.ExternalStorageCleaner.getInstance()
                
                enabled?.let { cleaner.setEnabled(it) }
                reservedSpaceMb?.let { cleaner.setReservedSpaceMb(it) }
                protectedHours?.let { cleaner.setProtectedHours(it) }
                
                activity?.runOnUiThread {
                    val isEnabled = cleaner.isEnabled
                    tvCdrCleanupBadge?.text = if (isEnabled) "ON" else "OFF"
                    tvCdrCleanupBadge?.setTextColor(resources.getColor(
                        if (isEnabled) R.color.status_running else R.color.text_muted, null))
                }
            } catch (e: Exception) {
                android.util.Log.w("RecordingControls", "Failed to save CDR config: ${e.message}")
            }
        }
    }
    
    private fun triggerCdrCleanup() {
        btnCdrCleanupNow?.isEnabled = false
        btnCdrCleanupNow?.text = getString(R.string.cdr_cleaning_in_progress)

        executor.submit {
            try {
                val cleaner = com.overdrive.app.storage.ExternalStorageCleaner.getInstance()
                val result = cleaner.forceCleanup(500 * 1024 * 1024) // Free 500MB

                activity?.runOnUiThread {
                    btnCdrCleanupNow?.isEnabled = true
                    btnCdrCleanupNow?.text = getString(R.string.action_clean_up_now)

                    if (result.isSuccess) {
                        val msg = getString(R.string.toast_cleanup_deleted, result.filesDeleted, com.overdrive.app.storage.ExternalStorageCleaner.formatSize(result.bytesFreed))
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        loadCdrConfig() // Refresh stats
                    } else {
                        Toast.makeText(context, getString(R.string.toast_cleanup_failed, result.error ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    btnCdrCleanupNow?.isEnabled = true
                    btnCdrCleanupNow?.text = getString(R.string.action_clean_up_now)
                    Toast.makeText(context, getString(R.string.toast_cleanup_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun markChanged() {
        // Don't mark changed during initialization
        if (isInitializing) {
            btnApplyQuality.isEnabled = false
            return
        }
        
        // Check if any pending value differs from saved value
        val qualityChanged = pendingQuality != null && pendingQuality != savedQuality
        val bitrateChanged = pendingBitrate != null && pendingBitrate != savedBitrate
        val codecChanged = pendingCodec != null && pendingCodec != savedCodec
        val storageLimitChanged = pendingStorageLimit != null && pendingStorageLimit != savedStorageLimit
        val storageTypeChanged = pendingStorageType != null && pendingStorageType != savedStorageType
        val modeChanged = pendingRecordingMode != null && pendingRecordingMode != savedRecordingMode
        val triggerLevelChanged = pendingTriggerLevel != null && pendingTriggerLevel != savedTriggerLevel
        val preRecordChanged = pendingPreRecord != null && pendingPreRecord != savedPreRecord
        val postRecordChanged = pendingPostRecord != null && pendingPostRecord != savedPostRecord
        val cdrEnabledChanged = pendingCdrEnabled != null && pendingCdrEnabled != savedCdrEnabled
        val cdrReservedChanged = pendingCdrReservedMb != null && pendingCdrReservedMb != savedCdrReservedMb
        val cdrProtectedChanged = pendingCdrProtectedHours != null && pendingCdrProtectedHours != savedCdrProtectedHours
        
        hasUnsavedChanges = qualityChanged || bitrateChanged || codecChanged || storageLimitChanged ||
                           storageTypeChanged || modeChanged || triggerLevelChanged || preRecordChanged || postRecordChanged ||
                           cdrEnabledChanged || cdrReservedChanged || cdrProtectedChanged
        btnApplyQuality.isEnabled = hasUnsavedChanges
    }
    
    private fun updateProximitySettingsVisibility() {
        val showProximity = pendingRecordingMode == "PROXIMITY_GUARD"
        cardProximitySettings.visibility = if (showProximity) View.VISIBLE else View.GONE
    }
    
    private fun applySettings() {
        // SOTA: Write directly to unified config file for cross-UID sync
        // This ensures web UI can see the changes immediately
        executor.submit {
            try {
                val configFile = java.io.File("/data/local/tmp/overdrive_config.json")
                val unified: org.json.JSONObject
                
                // Load existing config or create new
                if (configFile.exists()) {
                    val content = configFile.readText()
                    unified = org.json.JSONObject(content)
                } else {
                    unified = org.json.JSONObject()
                    unified.put("version", 1)
                }
                
                // Get or create recording section
                var recording = unified.optJSONObject("recording")
                if (recording == null) {
                    recording = org.json.JSONObject()
                }
                
                // Apply pending changes
                pendingQuality?.let { quality ->
                    if (quality != savedQuality) {
                        recording.put("quality", quality)
                        savedQuality = quality
                    }
                }
                
                pendingBitrate?.let { bitrate ->
                    if (bitrate != savedBitrate) {
                        recording.put("bitrate", bitrate)
                        savedBitrate = bitrate
                    }
                }
                
                pendingCodec?.let { codec ->
                    if (codec != savedCodec) {
                        recording.put("codec", codec)
                        savedCodec = codec
                    }
                }
                
                pendingRecordingMode?.let { mode ->
                    if (mode != savedRecordingMode) {
                        recording.put("mode", mode)
                        savedRecordingMode = mode
                    }
                }
                
                // Update unified config
                unified.put("recording", recording)
                
                // Apply proximity guard settings if changed
                pendingTriggerLevel?.let { level ->
                    if (level != savedTriggerLevel) {
                        var proximityGuard = unified.optJSONObject("proximityGuard")
                        if (proximityGuard == null) {
                            proximityGuard = org.json.JSONObject()
                        }
                        proximityGuard.put("triggerLevel", level)
                        unified.put("proximityGuard", proximityGuard)
                        savedTriggerLevel = level
                    }
                }
                
                pendingPreRecord?.let { pre ->
                    if (pre != savedPreRecord) {
                        var proximityGuard = unified.optJSONObject("proximityGuard")
                        if (proximityGuard == null) {
                            proximityGuard = org.json.JSONObject()
                        }
                        proximityGuard.put("preRecordSeconds", pre)
                        unified.put("proximityGuard", proximityGuard)
                        savedPreRecord = pre
                    }
                }
                
                pendingPostRecord?.let { post ->
                    if (post != savedPostRecord) {
                        var proximityGuard = unified.optJSONObject("proximityGuard")
                        if (proximityGuard == null) {
                            proximityGuard = org.json.JSONObject()
                        }
                        proximityGuard.put("postRecordSeconds", post)
                        unified.put("proximityGuard", proximityGuard)
                        savedPostRecord = post
                    }
                }
                
                // Apply storage limit if changed
                var cleanupMessage: String? = null
                pendingStorageLimit?.let { limit ->
                    if (limit != savedStorageLimit) {
                        var storage = unified.optJSONObject("storage")
                        if (storage == null) {
                            storage = org.json.JSONObject()
                        }
                        storage.put("recordingsLimitMb", limit)
                        unified.put("storage", storage)
                        
                        savedStorageLimit = limit
                    }
                }
                
                // Apply storage type if changed
                pendingStorageType?.let { type ->
                    if (type != savedStorageType) {
                        var storage = unified.optJSONObject("storage")
                        if (storage == null) {
                            storage = org.json.JSONObject()
                        }
                        storage.put("recordingsStorageType", type)
                        unified.put("storage", storage)
                        
                        savedStorageType = type
                    }
                }
                
                // Apply CDR cleanup settings if changed
                val cdrChanged = (pendingCdrEnabled != null && pendingCdrEnabled != savedCdrEnabled) ||
                                 (pendingCdrReservedMb != null && pendingCdrReservedMb != savedCdrReservedMb) ||
                                 (pendingCdrProtectedHours != null && pendingCdrProtectedHours != savedCdrProtectedHours)
                
                if (cdrChanged) {
                    try {
                        val cleaner = com.overdrive.app.storage.ExternalStorageCleaner.getInstance()
                        
                        pendingCdrEnabled?.let { enabled ->
                            if (enabled != savedCdrEnabled) {
                                cleaner.setEnabled(enabled)
                                savedCdrEnabled = enabled
                            }
                        }
                        pendingCdrReservedMb?.let { reserved ->
                            if (reserved != savedCdrReservedMb) {
                                cleaner.setReservedSpaceMb(reserved)
                                savedCdrReservedMb = reserved
                            }
                        }
                        pendingCdrProtectedHours?.let { hours ->
                            if (hours != savedCdrProtectedHours) {
                                cleaner.setProtectedHours(hours)
                                savedCdrProtectedHours = hours
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("RecordingControls", "CDR config save failed: ${e.message}")
                    }
                }
                
                unified.put("lastModified", System.currentTimeMillis())
                
                // Write to file
                configFile.writeText(unified.toString(2))
                
                // Make world-readable/writable for cross-UID access
                configFile.setReadable(true, false)
                configFile.setWritable(true, false)
                
                android.util.Log.d("RecordingControls", "Settings saved to unified config")
                
                // Also send to daemon via IPC to apply immediately to running pipeline
                val client = getOrCreateDaemonClient()
                if (client != null) {
                    pendingBitrate?.let { bitrate ->
                        client.setRecordingBitrateSync(bitrate)
                    }
                    pendingCodec?.let { codec ->
                        client.setRecordingCodecSync(codec)
                    }
                    pendingRecordingMode?.let { mode ->
                        client.setRecordingModeSync(mode)
                    }
                    pendingStorageType?.let { type ->
                        client.setRecordingsStorageTypeSync(type)
                    }
                    pendingStorageLimit?.let { limit ->
                        client.setRecordingsLimitMbSync(limit)
                    }
                }
                
                activity?.runOnUiThread {
                    hasUnsavedChanges = false
                    btnApplyQuality.isEnabled = false
                    
                    val msg = if (cleanupMessage != null) {
                        getString(R.string.toast_settings_applied_format, cleanupMessage)
                    } else {
                        getString(R.string.toast_settings_applied_short)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                android.util.Log.e("RecordingControls", "Failed to save settings: ${e.message}")
                activity?.runOnUiThread {
                    Toast.makeText(context, getString(R.string.toast_failed_save_settings), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun loadCurrentQualitySettings() {
        isInitializing = true
        
        // CRITICAL: Disable button FIRST before any toggle changes
        hasUnsavedChanges = false
        btnApplyQuality.isEnabled = false
        
        // SOTA: Load config in background to prevent UI lag (Skipped frames)
        executor.submit {
            var loadedBitrate = "MEDIUM"
            var loadedCodec = "H264"
            var loadedQuality = "NORMAL"
            var loadedStorageLimit = 500L
            var loadedRecordingMode = "NONE"
            var loadedTriggerLevel = "RED"
            var loadedPreRecord = 5
            var loadedPostRecord = 10
            
            try {
                val configFile = java.io.File("/data/local/tmp/overdrive_config.json")
                if (configFile.exists()) {
                    val content = configFile.readText()
                    val unified = org.json.JSONObject(content)
                    val recording = unified.optJSONObject("recording")
                    if (recording != null) {
                        val fileBitrate = recording.optString("bitrate", "")
                        if (fileBitrate == "LOW" || fileBitrate == "MEDIUM" || fileBitrate == "HIGH") {
                            loadedBitrate = fileBitrate
                        }
                        val fileCodec = recording.optString("codec", "")
                        if (fileCodec == "H264" || fileCodec == "H265") {
                            loadedCodec = fileCodec
                        }
                        val fileQuality = recording.optString("quality", "")
                        if (fileQuality == "LOW" || fileQuality == "REDUCED" || fileQuality == "NORMAL") {
                            loadedQuality = fileQuality
                        }
                        val fileMode = recording.optString("mode", "")
                        if (fileMode.isNotEmpty()) {
                            loadedRecordingMode = fileMode
                        }
                    }
                    
                    // Load proximity guard settings
                    val proximityGuard = unified.optJSONObject("proximityGuard")
                    if (proximityGuard != null) {
                        loadedTriggerLevel = proximityGuard.optString("triggerLevel", "RED")
                        loadedPreRecord = proximityGuard.optInt("preRecordSeconds", 5)
                        loadedPostRecord = proximityGuard.optInt("postRecordSeconds", 10)
                    }
                    
                    // Load storage limit
                    val storage = unified.optJSONObject("storage")
                    if (storage != null) {
                        loadedStorageLimit = storage.optLong("recordingsLimitMb", 500)
                    }
                    
                    android.util.Log.d("RecordingControls", "Loaded from unified config: bitrate=$loadedBitrate, codec=$loadedCodec, quality=$loadedQuality, mode=$loadedRecordingMode, storageLimit=$loadedStorageLimit")
                }
            } catch (e: Exception) {
                android.util.Log.w("RecordingControls", "Could not read unified config: ${e.message}")
            }
            
            // Apply loaded values on main thread
            val finalBitrate = loadedBitrate
            val finalCodec = loadedCodec
            val finalQuality = loadedQuality
            val finalStorageLimit = loadedStorageLimit
            val finalRecordingMode = loadedRecordingMode
            val finalTriggerLevel = loadedTriggerLevel
            val finalPreRecord = loadedPreRecord
            val finalPostRecord = loadedPostRecord
            
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                
                // Set saved values from file
                savedQuality = finalQuality
                savedBitrate = finalBitrate
                savedCodec = finalCodec
                savedStorageLimit = finalStorageLimit
                savedRecordingMode = finalRecordingMode
                savedTriggerLevel = finalTriggerLevel
                savedPreRecord = finalPreRecord
                savedPostRecord = finalPostRecord
                
                // Initialize pending values to saved values
                pendingQuality = savedQuality
                pendingBitrate = savedBitrate
                pendingCodec = savedCodec
                pendingStorageLimit = savedStorageLimit
                pendingRecordingMode = savedRecordingMode
                pendingTriggerLevel = savedTriggerLevel
                pendingPreRecord = savedPreRecord
                pendingPostRecord = savedPostRecord
                
                // Update UI toggles (listeners not added yet, so no callbacks)
                updateQualityToggle(savedQuality)
                updateBitrateToggle(savedBitrate)
                updateCodecToggle(savedCodec)
                updateRecordingModeRadio(savedRecordingMode)
                updateProximitySettingsUI(savedTriggerLevel, savedPreRecord, savedPostRecord)
                updateProximitySettingsVisibility()
                
                // Update storage limit slider
                sliderRecordingsLimit.value = savedStorageLimit.toFloat().coerceIn(100f, 1000f)
                tvRecordingsLimitValue.text = "${savedStorageLimit} MB"
                
                // Post to ensure button state is set AFTER toggle animations complete
                btnApplyQuality.post {
                    hasUnsavedChanges = false
                    btnApplyQuality.isEnabled = false
                    isInitializing = false
                }
            }
        }
    }
    
    private fun syncWithDaemon() {
        // SOTA: Read directly from unified config file for cross-UID sync
        // This is more reliable than IPC since both app and daemon can read the same file
        executor.submit {
            try {
                val configFile = java.io.File("/data/local/tmp/overdrive_config.json")
                if (configFile.exists()) {
                    val content = configFile.readText()
                    val unified = org.json.JSONObject(content)
                    val recording = unified.optJSONObject("recording")
                    
                    if (recording != null) {
                        val fileBitrate = recording.optString("bitrate", "MEDIUM")
                        val fileCodec = recording.optString("codec", "H264")
                        val fileQuality = recording.optString("quality", "NORMAL")
                        
                        // Check if values differ from current saved values
                        val bitrateChanged = fileBitrate != savedBitrate
                        val codecChanged = fileCodec != savedCodec
                        val qualityChanged = fileQuality != savedQuality
                        
                        if (bitrateChanged || codecChanged || qualityChanged) {
                            // Update saved values from file (source of truth)
                            savedBitrate = fileBitrate
                            savedCodec = fileCodec
                            savedQuality = fileQuality
                            
                            // Also update pending to match saved (no unsaved changes)
                            pendingBitrate = fileBitrate
                            pendingCodec = fileCodec
                            pendingQuality = fileQuality
                            
                            // Update UI on main thread
                            activity?.runOnUiThread {
                                isInitializing = true
                                updateBitrateToggle(fileBitrate)
                                updateCodecToggle(fileCodec)
                                updateQualityToggle(fileQuality)
                                
                                // Post to ensure button state is set AFTER toggle animations
                                btnApplyQuality.post {
                                    hasUnsavedChanges = false
                                    btnApplyQuality.isEnabled = false
                                    isInitializing = false
                                }
                            }
                            
                            android.util.Log.d("RecordingControls", "Synced from unified config: bitrate=$fileBitrate, codec=$fileCodec, quality=$fileQuality")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("RecordingControls", "Failed to sync from unified config: ${e.message}")
                // On error, ensure button stays disabled
                activity?.runOnUiThread {
                    hasUnsavedChanges = false
                    btnApplyQuality.isEnabled = false
                }
            }
        }
    }
    
    private fun updateQualityToggle(quality: String) {
        val buttonId = when (quality) {
            "LOW" -> R.id.btnRecQualityLow
            "REDUCED" -> R.id.btnRecQualityReduced
            else -> R.id.btnRecQualityNormal
        }
        toggleRecordingQuality.check(buttonId)
    }
    
    private fun updateBitrateToggle(bitrate: String) {
        val bitrateButtonId = when (bitrate) {
            "LOW" -> R.id.btnBitrateLow
            "HIGH" -> R.id.btnBitrateHigh
            else -> R.id.btnBitrateMedium
        }
        toggleRecordingBitrate.check(bitrateButtonId)
    }
    
    private fun updateCodecToggle(codec: String) {
        val codecButtonId = when (codec) {
            "H265" -> R.id.btnCodecH265
            else -> R.id.btnCodecH264
        }
        toggleVideoCodec.check(codecButtonId)
    }
    
    private fun loadStorageSettings() {
        try {
            val storageManager = com.overdrive.app.storage.StorageManager.getInstance()
            
            // SOTA: Refresh SD card detection if not currently available
            // This handles the case where SD card was inserted after app start
            if (!storageManager.isSdCardAvailable) {
                storageManager.refreshSdCard()
            }
            
            // Get SD card availability
            sdCardAvailable = storageManager.isSdCardAvailable
            sdCardPath = storageManager.sdCardPath
            
            // Get current storage type
            currentStorageType = storageManager.recordingsStorageType.name
            savedStorageType = currentStorageType
            pendingStorageType = currentStorageType
            
            // Update slider max based on storage type
            val maxLimit = if (currentStorageType == "SD_CARD") {
                com.overdrive.app.storage.StorageManager.getMaxLimitMbSdCard()
            } else {
                com.overdrive.app.storage.StorageManager.getMaxLimitMbInternal()
            }
            sliderRecordingsLimit.valueTo = maxLimit.toFloat()
            tvStorageMaxLabel?.text = "${maxLimit}MB"
            
            // Update storage type toggle
            isInitializing = true
            val btnId = if (currentStorageType == "SD_CARD") R.id.btnStorageSdCard else R.id.btnStorageInternal
            toggleRecordingsStorage.check(btnId)
            isInitializing = false
            
            // Update SD card button state
            view?.findViewById<MaterialButton>(R.id.btnStorageSdCard)?.isEnabled = sdCardAvailable
            
            // Update storage type UI
            updateStorageTypeUI()
            
            // Update CDR cleanup visibility
            updateCdrCleanupVisibility()
            
        } catch (e: Exception) {
            android.util.Log.w("RecordingControls", "Could not load storage settings: ${e.message}")
        }
    }
    
    private fun updateStorageTypeUI() {
        // Show/hide SD card status
        sdCardStatusLayout?.visibility = View.VISIBLE
        
        if (sdCardAvailable) {
            sdCardStatusDot?.setBackgroundResource(R.drawable.status_dot_online)
            tvSdCardStatus?.text = getString(R.string.sd_card_available)
        } else {
            sdCardStatusDot?.setBackgroundResource(R.drawable.status_dot_offline)
            tvSdCardStatus?.text = getString(R.string.storage_sd_not_detected)
        }
        
        // Update storage path display
        try {
            val storageManager = com.overdrive.app.storage.StorageManager.getInstance()
            val path = storageManager.recordingsPath
            val shortPath = path.replace("/storage/emulated/0/", "")
            tvStoragePathInfo?.text = getString(R.string.recordings_saved_to_path, shortPath)
        } catch (e: Exception) {
            tvStoragePathInfo?.text = getString(R.string.recordings_saved_default)
        }
        
        // Update slider max based on storage type
        val maxLimit = if (currentStorageType == "SD_CARD") {
            com.overdrive.app.storage.StorageManager.getMaxLimitMbSdCard()
        } else {
            com.overdrive.app.storage.StorageManager.getMaxLimitMbInternal()
        }
        sliderRecordingsLimit.valueTo = maxLimit.toFloat()
        tvStorageMaxLabel?.text = "${maxLimit}MB"
        
        // Clamp current value if needed
        if (sliderRecordingsLimit.value > maxLimit) {
            sliderRecordingsLimit.value = maxLimit.toFloat()
            tvRecordingsLimitValue.text = "${maxLimit} MB"
        }
    }
    
    private fun updateRecordingModeRadio(mode: String) {
        val radioId = when (mode) {
            "CONTINUOUS" -> R.id.radioModeContinuous
            "DRIVE_MODE" -> R.id.radioModeDriveMode
            "PROXIMITY_GUARD" -> R.id.radioModeProximityGuard
            else -> R.id.radioModeNone
        }
        radioGroupRecordingMode.check(radioId)
    }
    
    private fun updateProximitySettingsUI(triggerLevel: String, preRecord: Int, postRecord: Int) {
        spinnerTriggerLevel.setSelection(if (triggerLevel == "RED") 0 else 1)
        sliderPreRecord.value = preRecord.toFloat()
        sliderPostRecord.value = postRecord.toFloat()
        tvPreRecordValue.text = "$preRecord seconds"
        tvPostRecordValue.text = "$postRecord seconds"
    }
    
    private fun getOrCreateDaemonClient(): CameraDaemonClient? {
        if (daemonClient == null || !daemonClient!!.isConnected) {
            daemonClient = CameraDaemonClient()
            if (!daemonClient!!.connect()) {
                daemonClient = null
            }
        }
        return daemonClient
    }
    
    private fun observeViewModel() {
        // Storage info observer
        recordingViewModel.storageInfo.observe(viewLifecycleOwner) { info ->
            tvStorageUsed.text = "${info.usedFormatted} used"
            tvStorageLimit.text = "${savedStorageLimit} MB limit"
            
            // Update storage bar fill percentage
            val limitBytes = savedStorageLimit * 1024 * 1024
            val percentage = if (limitBytes > 0) {
                ((info.usedBytes.toFloat() / limitBytes) * 100).coerceIn(0f, 100f)
            } else 0f
            
            storageBarFill.post {
                val parent = storageBarFill.parent as? ViewGroup
                if (parent != null) {
                    val newWidth = (parent.width * percentage / 100).toInt()
                    val params = storageBarFill.layoutParams
                    params.width = newWidth
                    storageBarFill.layoutParams = params
                }
            }
        }
    }
    
    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
    
    override fun onResume() {
        super.onResume()
        recordingViewModel.updateStorageInfo()
        
        // Reload settings from daemon when fragment becomes visible
        // This ensures we pick up changes made from web UI or other sources
        if (!hasUnsavedChanges) {
            syncWithDaemon()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // Cleanup daemon client
        daemonClient?.disconnect()
        daemonClient = null
    }
}
