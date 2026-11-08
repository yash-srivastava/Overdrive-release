package com.overdrive.app.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.overlay.StatusOverlayChipOpacity
import com.overdrive.app.overlay.StatusOverlayService
import com.overdrive.app.overlay.StatusOverlayUiWriter
import com.overdrive.app.roadsense.config.RoadSenseConfig
import com.overdrive.app.roadsense.overlay.RoadSenseOverlayService

/**
 * Settings → Status overlay pane.
 *
 * Four switches for the app's floating status surfaces:
 *  - Camera/recording indicator (REC / PROX)
 *  - Instant replay indicator (CLIP)
 *  - Trip indicator (TRIP)
 *  - RoadSense pill / hazard card
 *
 * The three status-pill flags live in [UnifiedConfigManager]'s `statusOverlay`
 * section; RoadSense uses its existing `roadSense.overlayVisible` flag. Both
 * sections are file-backed so the app and daemon UIDs see one shared value.
 */
class SettingsOverlayFragment : Fragment() {
    private var roadSenseSwitch: SwitchMaterial? = null
    private var roadSenseRow: View? = null
    private var remoteCommunicationBinder: RemoteCommunicationSettingsBinder? = null
    private var roadSenseMasterOn = false
    private var applyingRoadSenseConfig = false

    // Guards the three status-pill listeners while a failed write reverts its
    // switch: setChecked() fires the listener even programmatically, and an
    // unguarded revert would re-enter persist() with the stale value —
    // ping-ponging forever if the write keeps failing.
    private var applyingStatusOverlayConfig = false
    private var chipOpacitySlider: Slider? = null
    private var opacitySlider: Slider? = null
    private var sizeSlider: Slider? = null
    private var chipOpacityValue: TextView? = null
    private var opacityValue: TextView? = null
    private var sizeValue: TextView? = null
    private var persistedChipOpacity = StatusOverlayChipOpacity.DEFAULT
    private var persistedOpacity = 100
    private var persistedSize = 18
    private var chipOpacitySaveGeneration = 0
    private var chipOpacityDragInProgress = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_overlay, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val swCamera = view.findViewById<SwitchMaterial>(R.id.swOverlayCamera) ?: return
        val swReplay = view.findViewById<SwitchMaterial>(R.id.swOverlayReplay) ?: return
        val swTrip = view.findViewById<SwitchMaterial>(R.id.swOverlayTrip) ?: return
        val swRoadSense =
            view.findViewById<SwitchMaterial>(R.id.swOverlayRoadSense) ?: return
        roadSenseSwitch = swRoadSense
        // Assign before the first refresh, or its row-dimming branch no-ops.
        roadSenseRow = view.findViewById(R.id.rowOverlayRoadSense)

        val cfg = UnifiedConfigManager.getStatusOverlay()
        swCamera.isChecked = cfg.optBoolean("cameraVisible", true)
        swReplay.isChecked = cfg.optBoolean("replayVisible", true)
        swTrip.isChecked = cfg.optBoolean("tripVisible", true)
        refreshRoadSenseSwitch(forceReload = true)

        // Make the whole row clickable as well for forgiveness on a wide
        // head-unit (toggling via the row, not just the thumb, is the BYD
        // muscle memory).
        view.findViewById<View>(R.id.rowOverlayCamera).setOnClickListener {
            swCamera.isChecked = !swCamera.isChecked
        }
        view.findViewById<View>(R.id.rowOverlayReplay).setOnClickListener {
            swReplay.isChecked = !swReplay.isChecked
        }
        view.findViewById<View>(R.id.rowOverlayTrip).setOnClickListener {
            swTrip.isChecked = !swTrip.isChecked
        }
        roadSenseRow?.setOnClickListener {
            swRoadSense.isChecked = !swRoadSense.isChecked
        }

        swCamera.setOnCheckedChangeListener { button, checked ->
            if (!applyingStatusOverlayConfig) persist("cameraVisible", checked, button)
        }
        swReplay.setOnCheckedChangeListener { button, checked ->
            if (!applyingStatusOverlayConfig) persist("replayVisible", checked, button)
        }
        swTrip.setOnCheckedChangeListener { button, checked ->
            if (!applyingStatusOverlayConfig) persist("tripVisible", checked, button)
        }
        swRoadSense.setOnCheckedChangeListener { _, checked ->
            if (!applyingRoadSenseConfig) persistRoadSense(checked)
        }
        bindIconSliders(view, cfg)
        remoteCommunicationBinder = RemoteCommunicationSettingsBinder(view)
    }

    override fun onResume() {
        super.onResume()
        // This preference is also exposed on the RoadSense page. Refresh when the
        // user returns so both entry points always display the same stored value.
        refreshRoadSenseSwitch(forceReload = true)
        remoteCommunicationBinder?.refresh()
    }

    override fun onDestroyView() {
        chipOpacitySaveGeneration++
        StatusOverlayService.clearChipOpacityPreview()
        remoteCommunicationBinder?.destroy()
        remoteCommunicationBinder = null
        roadSenseSwitch = null
        roadSenseRow = null
        chipOpacitySlider = null
        opacitySlider = null
        sizeSlider = null
        chipOpacityValue = null
        opacityValue = null
        sizeValue = null
        super.onDestroyView()
    }

    private fun bindIconSliders(view: View, cfg: org.json.JSONObject) {
        val chipOpacity = view.findViewById<Slider>(R.id.sliderOverlayChipOpacity) ?: return
        val opacity = view.findViewById<Slider>(R.id.sliderOverlayIconOpacity) ?: return
        val size = view.findViewById<Slider>(R.id.sliderOverlayIconSize) ?: return
        chipOpacitySlider = chipOpacity
        opacitySlider = opacity
        sizeSlider = size
        chipOpacityValue = view.findViewById(R.id.tvOverlayChipOpacity)
        opacityValue = view.findViewById(R.id.tvOverlayIconOpacity)
        sizeValue = view.findViewById(R.id.tvOverlayIconSize)

        val chipOpacityPct = StatusOverlayChipOpacity.clampStored(
            cfg.optInt("chipOpacity", StatusOverlayChipOpacity.DEFAULT)
        )
        val opacityPct = cfg.optInt("iconOpacity", 100).coerceIn(20, 100)
        val sizeDp = cfg.optInt("iconSizeDp", 18).coerceIn(12, 32)
        persistedChipOpacity = chipOpacityPct
        persistedOpacity = opacityPct
        persistedSize = sizeDp
        applyingStatusOverlayConfig = true
        // Material Slider throws on an off-step value; snap whatever is stored.
        chipOpacity.value = StatusOverlayChipOpacity.snapSliderValue(chipOpacityPct)
        opacity.value = ((100 - opacityPct) / 5 * 5).toFloat()
        size.value = (12 + (sizeDp - 12) / 2 * 2).toFloat()
        applyingStatusOverlayConfig = false
        renderChipOpacity(chipOpacityPct)
        renderOpacity(100 - opacityPct)
        renderSize(sizeDp)

        chipOpacity.addOnChangeListener { _, value, fromUser ->
            val opacity = value.toInt()
            renderChipOpacity(opacity)
            if (fromUser) {
                StatusOverlayService.previewChipOpacity(opacity)
                if (!chipOpacity.isPressed) {
                    commitChipOpacity(opacity)
                }
            }
        }
        opacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) renderOpacity(value.toInt())
        }
        size.addOnChangeListener { _, value, fromUser ->
            if (fromUser) renderSize(value.toInt())
        }
        val touch = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                if (slider === chipOpacitySlider) chipOpacityDragInProgress = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                if (applyingStatusOverlayConfig) return
                when (slider) {
                    chipOpacitySlider -> {
                        chipOpacityDragInProgress = false
                        commitChipOpacity(slider.value.toInt())
                    }
                    opacitySlider -> {
                        val next = (100 - slider.value.toInt()).coerceIn(20, 100)
                        if (next == persistedOpacity) return
                        persistInt("iconOpacity", next, persistedOpacity) { persistedOpacity = it }
                    }
                    else -> {
                        val next = slider.value.toInt().coerceIn(12, 32)
                        if (next == persistedSize) return
                        persistInt("iconSizeDp", next, persistedSize) { persistedSize = it }
                    }
                }
            }
        }
        chipOpacity.addOnSliderTouchListener(touch)
        opacity.addOnSliderTouchListener(touch)
        size.addOnSliderTouchListener(touch)
    }

    private fun commitChipOpacity(rawOpacity: Int) {
        val next = StatusOverlayChipOpacity.clampStored(rawOpacity)
        if (next == persistedChipOpacity) {
            if (!chipOpacityDragInProgress) {
                StatusOverlayService.clearChipOpacityPreview()
            }
            return
        }
        val saveGeneration = ++chipOpacitySaveGeneration
        persistChipOpacity(next, persistedChipOpacity, saveGeneration) {
            persistedChipOpacity = it
            if (saveGeneration == chipOpacitySaveGeneration && !chipOpacityDragInProgress) {
                StatusOverlayService.clearChipOpacityPreview()
            }
        }
    }

    private fun renderChipOpacity(opacityPct: Int) {
        chipOpacityValue?.text =
            getString(R.string.settings_overlay_chip_opacity_value, opacityPct)
    }

    private fun renderOpacity(transparency: Int) {
        opacityValue?.text = getString(R.string.settings_overlay_icon_opacity_value, transparency)
    }

    private fun renderSize(sizeDp: Int) {
        sizeValue?.text = getString(R.string.settings_overlay_icon_size_value, sizeDp)
    }

    private fun persistChipOpacity(
        value: Int,
        previous: Int,
        saveGeneration: Int,
        onOk: (Int) -> Unit
    ) {
        val appContext = context?.applicationContext
        StatusOverlayUiWriter.writeInt("chipOpacity", value) { ok ->
            if (ok) {
                onOk(value)
                appContext?.let { StatusOverlayService.startIfPermitted(it) }
            } else if (view != null) {
                if (saveGeneration != chipOpacitySaveGeneration) return@writeInt
                applyingStatusOverlayConfig = true
                chipOpacitySlider?.value = StatusOverlayChipOpacity.snapSliderValue(previous)
                renderChipOpacity(previous)
                StatusOverlayService.clearChipOpacityPreview()
                applyingStatusOverlayConfig = false
            }
        }
    }

    private fun persistInt(key: String, value: Int, previous: Int, onOk: (Int) -> Unit) {
        val appContext = context?.applicationContext
        StatusOverlayUiWriter.writeInt(key, value) { ok ->
            if (ok) {
                onOk(value)
                appContext?.let {
                    com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(it)
                }
            } else if (view != null) {
                applyingStatusOverlayConfig = true
                when (key) {
                    "iconOpacity" -> {
                        opacitySlider?.value = (100 - previous).toFloat()
                        renderOpacity(100 - previous)
                    }
                    else -> {
                        sizeSlider?.value = previous.toFloat()
                        renderSize(previous)
                    }
                }
                applyingStatusOverlayConfig = false
            }
        }
    }


    /**
     * Persist the flag OFF the UI thread ([StatusOverlayUiWriter] — the write
     * is a blocking IPC round-trip and updateSection is documented
     * off-looper-only), then nudge the overlay service so the toggle takes
     * effect now instead of on the next 3-10s poll tick.
     * StatusOverlayService.onStartCommand re-uses the existing instance and
     * cancels any in-flight delayed poll, firing one synchronously.
     *
     * On a failed write (daemon down and no local write possible) the switch
     * reverts, mirroring the RoadSense switch below — the control never lies
     * about the stored value.
     */
    private fun persist(key: String, value: Boolean, button: android.widget.CompoundButton) {
        val appContext = context?.applicationContext
        StatusOverlayUiWriter.write(key, value) { ok ->
            if (ok) {
                appContext?.let {
                    com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(it)
                }
            } else if (view != null) {  // fragment view still alive
                applyingStatusOverlayConfig = true
                button.isChecked = !value
                applyingStatusOverlayConfig = false
            }
        }
    }

    private fun persistRoadSense(visible: Boolean) {
        // setChecked() fires the listener even on a disabled switch, so the
        // master gate has to be enforced here too, not just via row.isEnabled.
        if (!roadSenseMasterOn) {
            refreshRoadSenseSwitch(forceReload = false)
            return
        }
        // Off the looper with one retry, exactly like persist() above. Writing inline here
        // blocked the UI thread on IPC, and an app-UID write that is deferred until the daemon
        // provisions the stable .lock inode returned false, so the switch silently snapped back
        // and RoadSense could not be enabled/disabled at all shortly after boot.
        StatusOverlayUiWriter.writeWith(
            "roadSense.overlayVisible",
            { ok ->
                if (ok) {
                    context?.let { RoadSenseOverlayService.syncWithConfig(it) }
                } else if (view != null) {  // fragment view still alive
                    refreshRoadSenseSwitch(forceReload = true)
                }
            }
        ) { RoadSenseConfig.setOverlayVisible(visible) }
    }

    private fun refreshRoadSenseSwitch(forceReload: Boolean) {
        val toggle = roadSenseSwitch ?: return
        val snapshot = try {
            RoadSenseConfig.snapshot(forceReload)
        } catch (_: Throwable) {
            // Unknown state: leave the control untouched but non-editable rather
            // than clickable-and-undimmed.
            roadSenseMasterOn = false
            setRoadSenseRowEnabled(false)
            return
        }
        applyingRoadSenseConfig = true
        toggle.isChecked = snapshot.overlayVisible
        applyingRoadSenseConfig = false
        // Dim the row when the master switch is off, or the toggle reads ON
        // with no overlay on screen.
        roadSenseMasterOn = snapshot.enabled
        setRoadSenseRowEnabled(snapshot.enabled)
    }

    private fun setRoadSenseRowEnabled(enabled: Boolean) {
        roadSenseSwitch?.isEnabled = enabled
        roadSenseRow?.let { row ->
            row.isEnabled = enabled
            row.alpha = if (enabled) 1f else 0.5f
        }
    }
}
