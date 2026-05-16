package com.overdrive.app.ui.dialog

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.overdrive.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import java.util.*

/**
 * Dialog for configuring surveillance time windows.
 * 
 * Features:
 * - Up to 3 time windows
 * - Start/end time pickers
 * - Day-of-week selection
 * - Supports overnight windows (e.g., 22:00 - 06:00)
 */
class TimeWindowDialogFragment : DialogFragment() {
    
    private lateinit var containerWindows: LinearLayout
    private lateinit var btnAddWindow: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    
    private val timeWindows = mutableListOf<TimeWindow>()
    private val windowViews = mutableListOf<View>()
    
    // Callback for when windows are saved
    var onWindowsSavedListener: ((List<TimeWindow>) -> Unit)? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Overdrive_FullScreenDialog)
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_time_window, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupButtons()
        
        // Add initial window if none exist
        if (timeWindows.isEmpty()) {
            addTimeWindow()
        } else {
            // Rebuild UI for existing windows
            timeWindows.forEach { window ->
                addTimeWindowView(window)
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    
    private fun initViews(view: View) {
        containerWindows = view.findViewById(R.id.containerWindows)
        btnAddWindow = view.findViewById(R.id.btnAddWindow)
        btnSave = view.findViewById(R.id.btnSave)
        btnCancel = view.findViewById(R.id.btnCancel)
    }
    
    private fun setupButtons() {
        btnAddWindow.setOnClickListener {
            if (timeWindows.size < MAX_WINDOWS) {
                addTimeWindow()
            }
        }
        
        btnSave.setOnClickListener {
            // Collect all window data from views
            collectWindowData()
            onWindowsSavedListener?.invoke(timeWindows.toList())
            dismiss()
        }
        
        btnCancel.setOnClickListener {
            dismiss()
        }
    }
    
    private fun addTimeWindow() {
        val window = TimeWindow(
            startHour = 22,
            startMinute = 0,
            endHour = 6,
            endMinute = 0,
            daysOfWeek = setOf(1, 2, 3, 4, 5, 6, 7) // All days
        )
        timeWindows.add(window)
        addTimeWindowView(window)
        updateAddButtonVisibility()
    }
    
    private fun addTimeWindowView(window: TimeWindow) {
        val inflater = LayoutInflater.from(context)
        val windowView = inflater.inflate(R.layout.item_time_window, containerWindows, false)
        
        val index = windowViews.size
        
        // Window title
        val tvTitle = windowView.findViewById<TextView>(R.id.tvWindowTitle)
        tvTitle.text = getString(R.string.time_window_index_title, index + 1)
        
        // Start time
        val tvStartTime = windowView.findViewById<TextView>(R.id.tvStartTime)
        tvStartTime.text = formatTime(window.startHour, window.startMinute)
        tvStartTime.setOnClickListener {
            showTimePicker(window.startHour, window.startMinute) { hour, minute ->
                timeWindows[index] = timeWindows[index].copy(startHour = hour, startMinute = minute)
                tvStartTime.text = formatTime(hour, minute)
            }
        }
        
        // End time
        val tvEndTime = windowView.findViewById<TextView>(R.id.tvEndTime)
        tvEndTime.text = formatTime(window.endHour, window.endMinute)
        tvEndTime.setOnClickListener {
            showTimePicker(window.endHour, window.endMinute) { hour, minute ->
                timeWindows[index] = timeWindows[index].copy(endHour = hour, endMinute = minute)
                tvEndTime.text = formatTime(hour, minute)
            }
        }
        
        // Day checkboxes
        val dayCheckboxes = listOf(
            windowView.findViewById<MaterialCheckBox>(R.id.cbMon),
            windowView.findViewById<MaterialCheckBox>(R.id.cbTue),
            windowView.findViewById<MaterialCheckBox>(R.id.cbWed),
            windowView.findViewById<MaterialCheckBox>(R.id.cbThu),
            windowView.findViewById<MaterialCheckBox>(R.id.cbFri),
            windowView.findViewById<MaterialCheckBox>(R.id.cbSat),
            windowView.findViewById<MaterialCheckBox>(R.id.cbSun)
        )
        
        // Set initial state (1=Mon, 7=Sun)
        dayCheckboxes.forEachIndexed { dayIndex, checkbox ->
            val dayOfWeek = dayIndex + 1
            checkbox.isChecked = window.daysOfWeek.contains(dayOfWeek)
        }
        
        // Remove button
        val btnRemove = windowView.findViewById<MaterialButton>(R.id.btnRemoveWindow)
        btnRemove.setOnClickListener {
            removeTimeWindow(index)
        }
        
        containerWindows.addView(windowView)
        windowViews.add(windowView)
    }
    
    private fun removeTimeWindow(index: Int) {
        if (timeWindows.size > 1) {
            timeWindows.removeAt(index)
            containerWindows.removeViewAt(index)
            windowViews.removeAt(index)
            
            // Update titles
            windowViews.forEachIndexed { i, view ->
                view.findViewById<TextView>(R.id.tvWindowTitle).text = getString(R.string.time_window_index_title, i + 1)
            }
            
            updateAddButtonVisibility()
        }
    }
    
    private fun collectWindowData() {
        windowViews.forEachIndexed { index, view ->
            val dayCheckboxes = listOf(
                view.findViewById<MaterialCheckBox>(R.id.cbMon),
                view.findViewById<MaterialCheckBox>(R.id.cbTue),
                view.findViewById<MaterialCheckBox>(R.id.cbWed),
                view.findViewById<MaterialCheckBox>(R.id.cbThu),
                view.findViewById<MaterialCheckBox>(R.id.cbFri),
                view.findViewById<MaterialCheckBox>(R.id.cbSat),
                view.findViewById<MaterialCheckBox>(R.id.cbSun)
            )
            
            val selectedDays = mutableSetOf<Int>()
            dayCheckboxes.forEachIndexed { dayIndex, checkbox ->
                if (checkbox.isChecked) {
                    selectedDays.add(dayIndex + 1)
                }
            }
            
            timeWindows[index] = timeWindows[index].copy(daysOfWeek = selectedDays)
        }
    }
    
    private fun showTimePicker(currentHour: Int, currentMinute: Int, onTimeSet: (Int, Int) -> Unit) {
        TimePickerDialog(
            requireContext(),
            R.style.Theme_Overdrive_TimePicker,
            { _, hour, minute -> onTimeSet(hour, minute) },
            currentHour,
            currentMinute,
            true // 24-hour format
        ).show()
    }
    
    private fun formatTime(hour: Int, minute: Int): String {
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }
    
    private fun updateAddButtonVisibility() {
        btnAddWindow.isEnabled = timeWindows.size < MAX_WINDOWS
        btnAddWindow.text = if (timeWindows.size < MAX_WINDOWS) {
            "+ Add Window (${timeWindows.size}/$MAX_WINDOWS)"
        } else {
            "Maximum $MAX_WINDOWS windows"
        }
    }
    
    /**
     * Set existing time windows.
     */
    fun setTimeWindows(windows: List<TimeWindow>) {
        timeWindows.clear()
        timeWindows.addAll(windows)
    }
    
    data class TimeWindow(
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
        val daysOfWeek: Set<Int> // 1=Monday, 7=Sunday
    )
    
    companion object {
        const val TAG = "TimeWindowDialog"
        const val MAX_WINDOWS = 3
        
        fun newInstance(): TimeWindowDialogFragment {
            return TimeWindowDialogFragment()
        }
    }
}
