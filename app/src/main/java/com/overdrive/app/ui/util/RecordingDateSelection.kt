package com.overdrive.app.ui.util

import androidx.fragment.app.FragmentManager
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.overdrive.app.R
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
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

    enum class RelativeDay { TODAY, YESTERDAY, OTHER }

    data class DayHeader(val dayStartMs: Long, val relativeDay: RelativeDay, val canAdvance: Boolean) {
        fun selectionLabel(format: DateFormat, getString: (Int) -> String): String = when (relativeDay) {
            RelativeDay.TODAY -> getString(R.string.recording_lib_date_today)
            RelativeDay.YESTERDAY -> getString(R.string.recording_lib_date_yesterday)
            RelativeDay.OTHER -> format.format(Date(dayStartMs))
        }
    }

    /** Interpret the selected civil date in the local calendar, not in picker UTC. */
    fun dayHeader(monthCalendar: Calendar, selectedDay: Int, now: Calendar = Calendar.getInstance()): DayHeader {
        val selected = (now.clone() as Calendar).apply {
            set(monthCalendar.get(Calendar.YEAR), monthCalendar.get(Calendar.MONTH), selectedDay, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return classifyDay(selected.timeInMillis, now)
    }

    fun dayHeader(dayMs: Long, now: Calendar = Calendar.getInstance()): DayHeader {
        val dayStart = (now.clone() as Calendar).apply {
            timeInMillis = dayMs
            clearTimeOfDay()
        }.timeInMillis
        return classifyDay(dayStart, now)
    }

    private fun classifyDay(dayStart: Long, now: Calendar): DayHeader {
        val today = (now.clone() as Calendar).apply { clearTimeOfDay() }
        val todayMs = today.timeInMillis
        val yesterdayMs = (today.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
            // Normalize each civil day independently: a DST gap can move midnight to 01:00.
            clearTimeOfDay()
        }.timeInMillis
        val relative = when (dayStart) {
            todayMs -> RelativeDay.TODAY
            yesterdayMs -> RelativeDay.YESTERDAY
            else -> RelativeDay.OTHER
        }
        return DayHeader(dayStart, relative, dayStart < todayMs)
    }

    fun startOfTodayMillis(now: Calendar = Calendar.getInstance()): Long =
        (now.clone() as Calendar).apply { clearTimeOfDay() }.timeInMillis

    private fun Calendar.clearTimeOfDay() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
