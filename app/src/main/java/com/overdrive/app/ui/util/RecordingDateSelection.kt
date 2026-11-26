package com.overdrive.app.ui.util

import androidx.fragment.app.FragmentManager
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.overdrive.app.R
import java.util.Calendar
import java.util.TimeZone

/** Shared date-picker and local-day navigation policy for the recording screens. */
internal object RecordingDateSelection {
    fun showPicker(
        manager: FragmentManager,
        monthCalendar: Calendar,
        selectedDay: Int,
        onSelected: (Calendar) -> Unit
    ) {
        val constraints = CalendarConstraints.Builder()
            .setEnd(MaterialDatePicker.todayInUtcMilliseconds())
            .setValidator(DateValidatorPointBackward.now())
            .build()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.recording_lib_pick_date)
            .setCalendarConstraints(constraints)
            .setSelection(utcMidnightMillis(monthCalendar, selectedDay))
            .build()
        picker.addOnPositiveButtonClickListener { onSelected(fromPickerMillis(it)) }
        picker.show(manager, "recording_lib_date_picker")
    }

    // MaterialDatePicker represents civil dates as UTC midnight, not local midnight.
    fun utcMidnightMillis(monthCalendar: Calendar, selectedDay: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(monthCalendar.get(Calendar.YEAR), monthCalendar.get(Calendar.MONTH), selectedDay, 0, 0, 0)
        }.timeInMillis

    fun fromPickerMillis(utcMs: Long): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }

    /** Shift by calendar days, returning null if the result would be in the future. */
    fun shiftDay(
        monthCalendar: Calendar,
        selectedDay: Int,
        delta: Int,
        now: Calendar = Calendar.getInstance()
    ): Calendar? {
        val candidate = (now.clone() as Calendar).apply {
            set(monthCalendar.get(Calendar.YEAR), monthCalendar.get(Calendar.MONTH), selectedDay)
            add(Calendar.DAY_OF_MONTH, delta)
        }
        val today = (now.clone() as Calendar).apply { clearTimeOfDay() }
        val candidateDay = (candidate.clone() as Calendar).apply { clearTimeOfDay() }
        return candidate.takeUnless { candidateDay.after(today) }
    }

    private fun Calendar.clearTimeOfDay() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
