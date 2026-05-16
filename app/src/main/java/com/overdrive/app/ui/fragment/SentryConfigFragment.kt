package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.overdrive.app.R
import com.overdrive.app.ui.viewmodel.SentryConfigViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Fragment for configuring Sentry surveillance settings.
 * Simplified UI with Apply button for explicit save.
 */
class SentryConfigFragment : Fragment() {
    
    private val viewModel: SentryConfigViewModel by viewModels()
    
    // Status
    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var tvStatus: TextView
    
    // Detection settings
    private lateinit var sliderDistance: Slider
    private lateinit var tvDistanceValue: TextView
    private lateinit var tvDistanceHint: TextView
    private lateinit var sliderSensitivity: Slider
    private lateinit var tvSensitivityValue: TextView
    private lateinit var tvSensitivityHint: TextView
    private lateinit var sliderFlashImmunity: Slider
    private lateinit var tvFlashImmunityValue: TextView
    private lateinit var cbDetectPerson: Chip
    private lateinit var cbDetectCar: Chip
    private lateinit var cbDetectBike: Chip
    
    // Distance to minObjectSize mapping (SOTA: Quadrant-Relative 15% Rule)
    // These values are relative to QUADRANT height in 2x2 mosaic
    // 1 = near (~3m), 5 = far (~15m)
    private val distanceMap = mapOf(
        1 to Triple(0.25f, "~3m (near)", "Near — only detects large/close objects (cars, groups)"),
        2 to Triple(0.18f, "~5m", "Close — good for parking lots, detects people nearby"),
        3 to Triple(0.12f, "~8m", "Balanced — detects people up to ~8m away"),
        4 to Triple(0.08f, "~10m", "Far — catches distant movement, may include passersby"),
        5 to Triple(0.05f, "~15m (far)", "Very Far — maximum range, use in quiet areas")
    )
    
    // Sensitivity controls motion detection thresholds (requiredBlocks + densityThreshold)
    // Block size is LOCKED at 32 - only density and required count vary
    // 1 = strict (large objects only), 5 = aggressive (any motion)
    private val sensitivityMap = mapOf(
        1 to Triple("Strict", 4, "Strict — large objects only (car/group), ignores single walkers"),
        2 to Triple("Conservative", 3, "Conservative — solid objects, good for windy conditions"),
        3 to Triple("Default", 2, "Balanced — triggers on walking people, ignores bugs/leaves"),
        4 to Triple("Sensitive", 2, "Sensitive — catches motion immediately on block entry"),
        5 to Triple("Aggressive", 1, "Aggressive — triggers on any motion, use indoors/garages only")
    )
    
    // Shadow rejection level names (Grayscale Grid approach)
    // Higher = stricter shadow/light filtering (ignores more faint changes)
    private val flashImmunityMap = mapOf(
        0 to "SENSITIVE", // shadowThreshold=40 - catches more motion
        1 to "NORMAL",    // shadowThreshold=55 - balanced (default)
        2 to "STRICT",    // shadowThreshold=70 - filters most lights
        3 to "MAX"        // shadowThreshold=90 - only high contrast
    )
    
    // Recording settings
    private lateinit var sliderPreBuffer: Slider
    private lateinit var tvPreBufferValue: TextView
    private lateinit var sliderPostBuffer: Slider
    private lateinit var tvPostBufferValue: TextView
    private lateinit var toggleBitrate: MaterialButtonToggleGroup
    private lateinit var toggleCodec: MaterialButtonToggleGroup
    
    // Storage settings
    private lateinit var sliderSurveillanceLimit: Slider
    private lateinit var tvSurveillanceLimitValue: TextView
    private lateinit var tvSurveillanceUsed: TextView
    private lateinit var tvSurveillanceLimit: TextView
    private lateinit var storageBarFill: View
    
    // Storage location selection
    private lateinit var toggleSurveillanceStorage: MaterialButtonToggleGroup
    private var survSdCardStatusLayout: View? = null
    private var survSdCardStatusDot: View? = null
    private var tvSurvSdCardStatus: TextView? = null
    private var tvSurvSdCardSpace: TextView? = null
    private var tvSurveillanceStoragePath: TextView? = null
    
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
    private var sliderCdrReserved: Slider? = null
    private var tvCdrReservedValue: TextView? = null
    private var sliderCdrProtected: Slider? = null
    private var tvCdrProtectedValue: TextView? = null
    private var btnCdrCleanupNow: MaterialButton? = null
    
    // Background executor for CDR operations
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    
    // CDR pending/saved values
    private var pendingCdrEnabled: Boolean? = null
    private var pendingCdrReservedMb: Long? = null
    private var pendingCdrProtectedHours: Int? = null
    private var savedCdrEnabled: Boolean = false
    private var savedCdrReservedMb: Long = 2000
    private var savedCdrProtectedHours: Int = 24
    
    // Safe Locations
    private var switchSafeLocations: SwitchMaterial? = null
    private var safeLocStatusLayout: android.view.View? = null
    private var tvSafeLocStatus: TextView? = null
    private var rvSafeLocations: androidx.recyclerview.widget.RecyclerView? = null
    private var btnAddCurrentLocation: MaterialButton? = null
    private var safeLocationAdapter: com.overdrive.app.ui.adapter.SafeLocationAdapter? = null
    
    // Apply button
    private lateinit var btnApply: MaterialButton
    
    private var hasUnsavedChanges = false
    
    // Flag to prevent listener callbacks during initialization
    private var isInitializing = false
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_sentry_config, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupListeners()
        observeViewModel()
        loadStorageSettings()
        
        viewModel.loadConfig()
    }
    
    private fun initViews(view: View) {
        // Status
        switchEnabled = view.findViewById(R.id.switchEnabled)
        tvStatus = view.findViewById(R.id.tvStatus)
        
        // Detection
        sliderDistance = view.findViewById(R.id.sliderDistance)
        tvDistanceValue = view.findViewById(R.id.tvDistanceValue)
        tvDistanceHint = view.findViewById(R.id.tvDistanceHint)
        sliderSensitivity = view.findViewById(R.id.sliderSensitivity)
        tvSensitivityValue = view.findViewById(R.id.tvSensitivityValue)
        tvSensitivityHint = view.findViewById(R.id.tvSensitivityHint)
        sliderFlashImmunity = view.findViewById(R.id.sliderFlashImmunity)
        tvFlashImmunityValue = view.findViewById(R.id.tvFlashImmunityValue)
        cbDetectPerson = view.findViewById(R.id.cbDetectPerson)
        cbDetectCar = view.findViewById(R.id.cbDetectCar)
        cbDetectBike = view.findViewById(R.id.cbDetectBike)
        
        // Recording
        sliderPreBuffer = view.findViewById(R.id.sliderPreBuffer)
        tvPreBufferValue = view.findViewById(R.id.tvPreBufferValue)
        sliderPostBuffer = view.findViewById(R.id.sliderPostBuffer)
        tvPostBufferValue = view.findViewById(R.id.tvPostBufferValue)
        toggleBitrate = view.findViewById(R.id.toggleBitrate)
        toggleCodec = view.findViewById(R.id.toggleCodec)
        
        // Storage
        sliderSurveillanceLimit = view.findViewById(R.id.sliderSurveillanceLimit)
        tvSurveillanceLimitValue = view.findViewById(R.id.tvSurveillanceLimitValue)
        tvSurveillanceUsed = view.findViewById(R.id.tvSurveillanceUsed)
        tvSurveillanceLimit = view.findViewById(R.id.tvSurveillanceLimit)
        storageBarFill = view.findViewById(R.id.storageBarFill)
        
        // Storage location selection
        toggleSurveillanceStorage = view.findViewById(R.id.toggleSurveillanceStorage)
        survSdCardStatusLayout = view.findViewById(R.id.survSdCardStatusLayout)
        survSdCardStatusDot = view.findViewById(R.id.survSdCardStatusDot)
        tvSurvSdCardStatus = view.findViewById(R.id.tvSurvSdCardStatus)
        tvSurvSdCardSpace = view.findViewById(R.id.tvSurvSdCardSpace)
        tvSurveillanceStoragePath = view.findViewById(R.id.tvSurveillanceStoragePath)
        
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
        
        // Apply
        btnApply = view.findViewById(R.id.btnApply)
        
        // Safe Locations
        switchSafeLocations = view.findViewById(R.id.switchSafeLocations)
        safeLocStatusLayout = view.findViewById(R.id.safeLocStatusLayout)
        tvSafeLocStatus = view.findViewById(R.id.tvSafeLocStatus)
        rvSafeLocations = view.findViewById(R.id.rvSafeLocations)
        btnAddCurrentLocation = view.findViewById(R.id.btnAddCurrentLocation)
        
        // Setup safe location RecyclerView
        safeLocationAdapter = com.overdrive.app.ui.adapter.SafeLocationAdapter(
            onToggle = { id, enabled -> toggleSafeZone(id, enabled) },
            onRadiusChanged = { id, radius -> updateSafeZoneRadius(id, radius) },
            onDelete = { id -> deleteSafeZone(id) }
        )
        rvSafeLocations?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        rvSafeLocations?.adapter = safeLocationAdapter
    }
    
    private fun setupListeners() {
        // Enable/disable surveillance (immediate effect)
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                viewModel.setEnabled(isChecked)
            }
        }
        
        // Distance slider
        sliderDistance.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                val distance = value.toInt()
                tvDistanceValue.text = distanceMap[distance]?.second ?: "~8m"
                tvDistanceHint.text = distanceMap[distance]?.third ?: ""
                markChanged()
            }
        }
        
        // Sensitivity slider
        sliderSensitivity.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                val sensitivity = value.toInt()
                tvSensitivityValue.text = sensitivityMap[sensitivity]?.first ?: "Default"
                tvSensitivityHint.text = sensitivityMap[sensitivity]?.third ?: ""
                markChanged()
            }
        }
        
        // Flash immunity slider
        sliderFlashImmunity.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                val level = value.toInt()
                tvFlashImmunityValue.text = flashImmunityMap[level] ?: "MEDIUM"
                markChanged()
            }
        }
        
        // Detection class checkboxes
        cbDetectPerson.setOnCheckedChangeListener { _, _ -> if (!isInitializing) markChanged() }
        cbDetectCar.setOnCheckedChangeListener { _, _ -> if (!isInitializing) markChanged() }
        cbDetectBike.setOnCheckedChangeListener { _, _ -> if (!isInitializing) markChanged() }
        
        // Pre-buffer slider
        sliderPreBuffer.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                tvPreBufferValue.text = "${value.toInt()}s"
                markChanged()
            }
        }
        
        // Post-buffer slider
        sliderPostBuffer.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                tvPostBufferValue.text = "${value.toInt()}s"
                markChanged()
            }
        }
        
        // Bitrate toggle
        toggleBitrate.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked && !isInitializing) markChanged()
        }
        
        // Codec toggle
        toggleCodec.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked && !isInitializing) markChanged()
        }
        
        // Storage limit slider
        sliderSurveillanceLimit.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                tvSurveillanceLimitValue.text = "${value.toInt()} MB"
                tvSurveillanceLimit.text = "${value.toInt()} MB limit"
                markChanged()
            }
        }
        
        // Storage type toggle
        toggleSurveillanceStorage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isInitializing) {
                val newType = when (checkedId) {
                    R.id.btnSurvStorageInternal -> "INTERNAL"
                    R.id.btnSurvStorageSdCard -> "SD_CARD"
                    else -> "INTERNAL"
                }
                
                // Check if SD card is available when selecting SD card
                if (newType == "SD_CARD" && !sdCardAvailable) {
                    Toast.makeText(context, getString(R.string.toast_sd_card_not_available), Toast.LENGTH_SHORT).show()
                    // Revert to internal
                    toggleSurveillanceStorage.check(R.id.btnSurvStorageInternal)
                    return@addOnButtonCheckedListener
                }
                
                currentStorageType = newType
                updateStorageTypeUI()
                updateCdrCleanupVisibility()
                markChanged()
            }
        }
        
        // CDR Cleanup listeners
        setupCdrCleanupListeners()
        
        // Safe Location listeners
        setupSafeLocationListeners()
        
        // Apply button
        btnApply.setOnClickListener {
            applySettings()
        }
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
        cardCdrCleanup?.visibility = if (showCard) android.view.View.VISIBLE else android.view.View.GONE
        
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
                android.util.Log.w("SentryConfig", "Failed to load CDR config: ${e.message}")
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
                android.util.Log.w("SentryConfig", "Failed to save CDR config: ${e.message}")
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
        hasUnsavedChanges = true
        btnApply.isEnabled = true
    }
    
    // ==================== SAFE LOCATIONS ====================
    
    private fun setupSafeLocationListeners() {
        switchSafeLocations?.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                viewModel.toggleSafeLocations(isChecked)
                safeLocStatusLayout?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        
        btnAddCurrentLocation?.setOnClickListener {
            addCurrentLocationAsSafe()
        }
        
        // Load safe locations on init
        loadSafeLocations()
    }
    
    private fun loadSafeLocations() {
        viewModel.loadSafeLocations { data ->
            activity?.runOnUiThread {
                isInitializing = true
                switchSafeLocations?.isChecked = data.optBoolean("featureEnabled", false)
                safeLocStatusLayout?.visibility = if (data.optBoolean("featureEnabled", false))
                    android.view.View.VISIBLE else android.view.View.GONE
                
                // Update status text
                if (data.optBoolean("inSafeZone", false)) {
                    tvSafeLocStatus?.text = getString(R.string.safe_loc_in_zone, data.optString("currentZone", ""))
                } else if (data.optBoolean("hasGps", false) && data.optInt("zoneCount", 0) > 0) {
                    tvSafeLocStatus?.text = getString(R.string.safe_loc_outside, data.optInt("nearestDistanceM", 0))
                } else {
                    tvSafeLocStatus?.text = if (data.optInt("zoneCount", 0) == 0) getString(R.string.safe_loc_no_zones) else getString(R.string.safe_loc_waiting_gps)
                }
                
                // Update zone list
                val zones = data.optJSONArray("zones")
                val items = mutableListOf<com.overdrive.app.ui.adapter.SafeLocationItem>()
                if (zones != null) {
                    for (i in 0 until zones.length()) {
                        val z = zones.getJSONObject(i)
                        items.add(com.overdrive.app.ui.adapter.SafeLocationItem(
                            id = z.optString("id"),
                            name = z.optString("name"),
                            lat = z.optDouble("lat"),
                            lng = z.optDouble("lng"),
                            radiusM = z.optInt("radiusM", 150),
                            enabled = z.optBoolean("enabled", true)
                        ))
                    }
                }
                safeLocationAdapter?.submitList(items)
                isInitializing = false
            }
        }
    }
    
    private fun addCurrentLocationAsSafe() {
        // Show dialog for zone name
        val input = android.widget.EditText(requireContext())
        input.hint = getString(R.string.dialog_add_safe_location_hint)
        input.setText(getString(R.string.dialog_add_safe_location_default))

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_add_safe_location_title))
            .setMessage(getString(R.string.dialog_add_safe_location_message))
            .setView(input)
            .setPositiveButton(getString(R.string.dialog_add)) { _, _ ->
                val name = input.text.toString().ifBlank { getString(R.string.safe_loc_unnamed) }
                viewModel.addCurrentLocationAsSafe(name, 150) { success ->
                    activity?.runOnUiThread {
                        if (success) {
                            Toast.makeText(context, getString(R.string.toast_zone_added, name), Toast.LENGTH_SHORT).show()
                            loadSafeLocations()
                        } else {
                            Toast.makeText(context, getString(R.string.toast_zone_add_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
    
    private fun toggleSafeZone(id: String, enabled: Boolean) {
        viewModel.updateSafeZone(id, org.json.JSONObject().apply { put("enabled", enabled) })
    }
    
    private fun updateSafeZoneRadius(id: String, radius: Int) {
        viewModel.updateSafeZone(id, org.json.JSONObject().apply { put("radiusM", radius) })
    }
    
    private fun deleteSafeZone(id: String) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_delete_zone_title))
            .setMessage(getString(R.string.dialog_delete_zone_message))
            .setPositiveButton(getString(R.string.dialog_delete)) { _, _ ->
                viewModel.deleteSafeZone(id) {
                    activity?.runOnUiThread { loadSafeLocations() }
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
    
    private fun applySettings() {
        // Get distance from slider
        val distance = sliderDistance.value.toInt()
        
        // Get sensitivity from slider (1-5)
        val sensitivity = sliderSensitivity.value.toInt()
        
        // Get flash immunity level
        val flashImmunity = sliderFlashImmunity.value.toInt()
        
        val bitrate = when (toggleBitrate.checkedButtonId) {
            R.id.btnBitrateLow -> "LOW"
            R.id.btnBitrateMedium -> "MEDIUM"
            R.id.btnBitrateHigh -> "HIGH"
            else -> "MEDIUM"
        }
        
        val codec = when (toggleCodec.checkedButtonId) {
            R.id.btnCodecH264 -> "H264"
            R.id.btnCodecH265 -> "H265"
            else -> "H264"
        }
        
        // Get storage limit
        val storageLimitMb = sliderSurveillanceLimit.value.toLong()
        
        // Apply all settings
        // Distance slider (1-5) controls minObjectSize via IPC server mapping
        // Sensitivity slider (1-5) controls requiredBlocks via IPC server mapping
        viewModel.setDistance(distance)
        viewModel.setSensitivity(sensitivity)
        viewModel.setFlashImmunity(flashImmunity)
        viewModel.setDetectPerson(cbDetectPerson.isChecked)
        viewModel.setDetectCar(cbDetectCar.isChecked)
        viewModel.setDetectBike(cbDetectBike.isChecked)
        viewModel.setPreEventBuffer(sliderPreBuffer.value.toInt())
        viewModel.setPostEventBuffer(sliderPostBuffer.value.toInt())
        viewModel.setBitrate(bitrate)
        viewModel.setCodec(codec)
        
        // Save storage limit to unified config and trigger cleanup
        saveStorageLimit(storageLimitMb)
        
        // Save CDR cleanup settings if changed
        saveCdrSettings()
        
        // Save to server
        viewModel.saveConfig()
        
        hasUnsavedChanges = false
        btnApply.isEnabled = false
        
        Toast.makeText(context, getString(R.string.toast_settings_applied_short), Toast.LENGTH_LONG).show()
    }
    
    private fun saveCdrSettings() {
        val cdrChanged = (pendingCdrEnabled != null && pendingCdrEnabled != savedCdrEnabled) ||
                         (pendingCdrReservedMb != null && pendingCdrReservedMb != savedCdrReservedMb) ||
                         (pendingCdrProtectedHours != null && pendingCdrProtectedHours != savedCdrProtectedHours)
        
        if (cdrChanged) {
            executor.submit {
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
                    android.util.Log.w("SentryConfig", "CDR config save failed: ${e.message}")
                }
            }
        }
    }
    
    private fun saveStorageLimit(limitMb: Long): String? {
        try {
            // Send storage type and limit to daemon via IPC so the daemon's
            // StorageManager picks up the change (runs in daemon process)
            viewModel.saveSurveillanceStorage(currentStorageType, limitMb)
            
            android.util.Log.d("SentryConfig", "Sent surveillance storage via IPC: type=$currentStorageType, limit=${limitMb}MB")
            
        } catch (e: Exception) {
            android.util.Log.e("SentryConfig", "Failed to save storage settings: ${e.message}")
        }
        
        return null
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
            currentStorageType = storageManager.surveillanceStorageType.name
            
            // Get storage limit
            var limitMb = storageManager.surveillanceLimitMb
            
            // Update slider max based on storage type
            val maxLimit = if (currentStorageType == "SD_CARD") {
                com.overdrive.app.storage.StorageManager.getMaxLimitMbSdCard()
            } else {
                com.overdrive.app.storage.StorageManager.getMaxLimitMbInternal()
            }
            sliderSurveillanceLimit.valueTo = maxLimit.toFloat()
            
            sliderSurveillanceLimit.value = limitMb.toFloat().coerceIn(100f, maxLimit.toFloat())
            tvSurveillanceLimitValue.text = "${limitMb} MB"
            tvSurveillanceLimit.text = "${limitMb} MB limit"
            
            // Update storage type toggle
            isInitializing = true
            val btnId = if (currentStorageType == "SD_CARD") R.id.btnSurvStorageSdCard else R.id.btnSurvStorageInternal
            toggleSurveillanceStorage.check(btnId)
            isInitializing = false
            
            // Update SD card button state
            view?.findViewById<MaterialButton>(R.id.btnSurvStorageSdCard)?.isEnabled = sdCardAvailable
            
            // Update storage type UI
            updateStorageTypeUI()
            
            // Update CDR cleanup visibility
            updateCdrCleanupVisibility()
            
            // Update storage usage display
            updateStorageDisplay(limitMb)
            
        } catch (e: Exception) {
            android.util.Log.w("SentryConfig", "Could not load storage settings: ${e.message}")
        }
    }
    
    private fun updateStorageTypeUI() {
        // Show/hide SD card status
        survSdCardStatusLayout?.visibility = View.VISIBLE
        
        if (sdCardAvailable) {
            survSdCardStatusDot?.setBackgroundResource(R.drawable.status_dot_online)
            tvSurvSdCardStatus?.text = getString(R.string.sd_card_available)

            // Show free space
            try {
                val storageManager = com.overdrive.app.storage.StorageManager.getInstance()
                val freeSpace = storageManager.sdCardFreeSpace
                val totalSpace = storageManager.sdCardTotalSpace
                tvSurvSdCardSpace?.text = getString(R.string.sd_card_free_format, com.overdrive.app.storage.StorageManager.formatSize(freeSpace))
            } catch (e: Exception) {
                tvSurvSdCardSpace?.text = ""
            }
        } else {
            survSdCardStatusDot?.setBackgroundResource(R.drawable.status_dot_offline)
            tvSurvSdCardStatus?.text = getString(R.string.storage_sd_not_detected)
            tvSurvSdCardSpace?.text = ""
        }

        // Update storage path display
        try {
            val storageManager = com.overdrive.app.storage.StorageManager.getInstance()
            val path = storageManager.surveillancePath
            val shortPath = path.replace("/storage/emulated/0/", "")
            tvSurveillanceStoragePath?.text = getString(R.string.events_saved_to_path, shortPath)
        } catch (e: Exception) {
            tvSurveillanceStoragePath?.text = getString(R.string.events_saved_default)
        }
        
        // Update slider max based on storage type
        val maxLimit = if (currentStorageType == "SD_CARD") {
            com.overdrive.app.storage.StorageManager.getMaxLimitMbSdCard()
        } else {
            com.overdrive.app.storage.StorageManager.getMaxLimitMbInternal()
        }
        sliderSurveillanceLimit.valueTo = maxLimit.toFloat()
        
        // Clamp current value if needed
        if (sliderSurveillanceLimit.value > maxLimit) {
            sliderSurveillanceLimit.value = maxLimit.toFloat()
            tvSurveillanceLimitValue.text = "${maxLimit} MB"
        }
    }
    
    private fun updateStorageDisplay(limitMb: Long) {
        try {
            val storageManager = com.overdrive.app.storage.StorageManager.getInstance()
            val usedBytes = storageManager.surveillanceSize
            val usedFormatted = com.overdrive.app.storage.StorageManager.formatSize(usedBytes)
            
            tvSurveillanceUsed.text = "$usedFormatted used"
            tvSurveillanceLimit.text = "${limitMb} MB limit"
            
            // Update storage bar fill percentage
            val limitBytes = limitMb * 1024 * 1024
            val percentage = if (limitBytes > 0) {
                ((usedBytes.toFloat() / limitBytes) * 100).coerceIn(0f, 100f)
            } else 0f
            
            storageBarFill.post {
                val parent = storageBarFill.parent as? android.view.ViewGroup
                if (parent != null) {
                    val newWidth = (parent.width * percentage / 100).toInt()
                    val params = storageBarFill.layoutParams
                    params.width = newWidth
                    storageBarFill.layoutParams = params
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SentryConfig", "Could not update storage display: ${e.message}")
        }
    }
    
    private fun observeViewModel() {
        viewModel.config.observe(viewLifecycleOwner) { config ->
            updateUiFromConfig(config)
        }
        
        viewModel.status.observe(viewLifecycleOwner) { status ->
            updateStatusDisplay(status)
        }
        
        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateUiFromConfig(config: SentryConfigViewModel.SentryConfig) {
        isInitializing = true
        
        // Update values
        switchEnabled.isChecked = config.enabled
        
        // Distance slider - convert minObjectSize to distance (1-5)
        val distance = sizeToDistance(config.minObjectSize)
        sliderDistance.value = distance.toFloat()
        tvDistanceValue.text = distanceMap[distance]?.second ?: "~8m"
        tvDistanceHint.text = distanceMap[distance]?.third ?: ""
        
        // Sensitivity slider (1-5, default 3)
        val sensitivity = config.sensitivity.coerceIn(1, 5)
        sliderSensitivity.value = sensitivity.toFloat()
        tvSensitivityValue.text = sensitivityMap[sensitivity]?.first ?: "Default"
        tvSensitivityHint.text = sensitivityMap[sensitivity]?.third ?: ""
        
        // Flash immunity slider
        val flashImmunity = config.flashImmunity.coerceIn(0, 3)
        sliderFlashImmunity.value = flashImmunity.toFloat()
        tvFlashImmunityValue.text = flashImmunityMap[flashImmunity] ?: "MEDIUM"
        
        // Detection classes
        cbDetectPerson.isChecked = config.detectPerson
        cbDetectCar.isChecked = config.detectCar
        cbDetectBike.isChecked = config.detectBike
        
        // Recording
        sliderPreBuffer.value = config.preEventBufferSeconds.toFloat().coerceIn(2f, 15f)
        tvPreBufferValue.text = "${config.preEventBufferSeconds}s"
        sliderPostBuffer.value = config.postEventBufferSeconds.toFloat().coerceIn(5f, 30f)
        tvPostBufferValue.text = "${config.postEventBufferSeconds}s"
        
        // Bitrate
        val bitrateBtnId = when (config.bitrate) {
            "LOW" -> R.id.btnBitrateLow
            "MEDIUM" -> R.id.btnBitrateMedium
            "HIGH" -> R.id.btnBitrateHigh
            else -> R.id.btnBitrateMedium
        }
        toggleBitrate.check(bitrateBtnId)
        
        // Codec
        val codecBtnId = when (config.codec) {
            "H264" -> R.id.btnCodecH264
            "H265" -> R.id.btnCodecH265
            else -> R.id.btnCodecH264
        }
        toggleCodec.check(codecBtnId)
        
        // Reset unsaved state after loading
        hasUnsavedChanges = false
        btnApply.isEnabled = false
        
        isInitializing = false
    }
    
    // Convert minObjectSize to distance slider value (1-5)
    // SOTA: Quadrant-relative thresholds
    // 1 = near (large threshold), 5 = far (small threshold)
    private fun sizeToDistance(size: Float): Int {
        return when {
            size >= 0.22f -> 1  // ~3m (near)
            size >= 0.15f -> 2  // ~5m
            size >= 0.10f -> 3  // ~8m
            size >= 0.06f -> 4  // ~10m
            else -> 5           // ~15m (far)
        }
    }
    
    private fun updateStatusDisplay(status: SentryConfigViewModel.SentryStatus) {
        val statusText = when {
            !status.enabled -> getString(R.string.sentry_status_disabled)
            status.recording -> getString(R.string.sentry_status_recording)
            status.active -> getString(R.string.sentry_status_active)
            else -> getString(R.string.sentry_status_waiting)
        }
        tvStatus.text = statusText
        
        val colorRes = when {
            status.recording -> R.color.status_error
            status.active -> R.color.status_running
            status.enabled -> R.color.status_starting
            else -> R.color.text_secondary
        }
        tvStatus.setTextColor(resources.getColor(colorRes, null))
    }
}
