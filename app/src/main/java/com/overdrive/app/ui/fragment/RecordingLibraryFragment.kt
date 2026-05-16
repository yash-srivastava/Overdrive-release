package com.overdrive.app.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.ui.adapter.CalendarAdapter
import com.overdrive.app.ui.adapter.RecordingAdapter
import com.overdrive.app.ui.model.RecordingFile
import com.overdrive.app.ui.util.RecordingScanner
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.overdrive.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Fragment for browsing recorded videos with calendar view.
 * SOTA: Uses background thread for scanning to prevent UI lag.
 */
class RecordingLibraryFragment : Fragment() {
    
    companion object {
        private const val TAG = "RecordingLibrary"
    }
    
    private lateinit var tvCurrentMonth: TextView
    private lateinit var btnPrevMonth: ImageButton
    private lateinit var btnNextMonth: ImageButton
    private lateinit var recyclerCalendar: RecyclerView
    private lateinit var tvSelectedDate: TextView
    private lateinit var recyclerRecordings: RecyclerView
    private lateinit var tvEmptyState: TextView
    private var emptyStateContainer: LinearLayout? = null
    private var chipFilterAll: Chip? = null
    private var chipFilterNormal: Chip? = null
    private var chipFilterSentry: Chip? = null
    private var chipFilterProximity: Chip? = null

    // Calendar collapse (item 7) — full month grid is collapsed by default;
    // an always-visible week strip lives in its own row above and provides
    // day-by-day navigation without needing to expand the month.
    private var calendarBody: View? = null
    private var btnCalendarCollapse: ImageButton? = null
    private var recyclerWeekStrip: RecyclerView? = null
    private var btnPrevWeek: ImageButton? = null
    private var btnNextWeek: ImageButton? = null
    // Separate adapter so the week strip can show "today" and "selected"
    // independently from the month grid (which lives behind the collapse).
    private val weekStripAdapter = CalendarAdapter { day -> onWeekDaySelected(day) }
    // The Calendar instance used to build the 7-day window. Independent of
    // the month-level `calendar` so paging the week strip doesn't fight with
    // month prev/next. weekAnchor always points at the SELECTED day.
    private val weekAnchor: Calendar = Calendar.getInstance()

    // v3 actor / severity filter chips (item 7) — two rows.
    // Row 1 ("What:") covers actor class. Row 2 ("Severity:") covers severity.
    // Each row's "Any" chip clears that row only.
    private var chipActorAny: Chip? = null
    private var chipActorPerson: Chip? = null
    private var chipActorVehicle: Chip? = null
    private var chipActorBike: Chip? = null
    private var chipActorAnimal: Chip? = null
    private var chipSevAny: Chip? = null
    private var chipSevAlert: Chip? = null
    private var chipSevCritical: Chip? = null
    
    // Multi-select toolbar
    private var selectToolbar: LinearLayout? = null
    private var tvSelectedCount: TextView? = null
    private var btnSelectAll: View? = null
    private var btnDeleteSelected: View? = null
    private var btnCancelSelect: View? = null
    
    private val calendarAdapter = CalendarAdapter { day -> onDaySelected(day) }
    private lateinit var recordingAdapter: RecordingAdapter
    
    private val calendar = Calendar.getInstance()
    private var selectedDay = calendar.get(Calendar.DAY_OF_MONTH)
    private var currentFilter = RecordingFilter.ALL

    // v3 actor + severity filter state (item 7)
    private val actorClassFilter = mutableSetOf<String>()  // lowercased class group names
    private val severityFilter = mutableSetOf<String>()    // "ALERT" / "CRITICAL"
    // SOTA pattern: calendar lives behind a toggle. Collapsed by default so the
    // user lands on the grid; tap the chevron in the month bar to expand.
    private var calendarCollapsed = true
    
    private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    
    // SOTA: Background executor for scanning operations
    private var scanExecutor = Executors.newSingleThreadExecutor()
    
    enum class RecordingFilter {
        ALL, NORMAL, SENTRY, PROXIMITY
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure executor is available (may have been shutdown on previous destroy)
        if (scanExecutor.isShutdown) {
            scanExecutor = Executors.newSingleThreadExecutor()
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_recording_library, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Ensure executor is available (may have been shutdown in onDestroyView)
        if (scanExecutor.isShutdown || scanExecutor.isTerminated) {
            scanExecutor = Executors.newSingleThreadExecutor()
        }
        
        initViews(view)
        setupCalendar()
        setupRecordingsList()
        setupClickListeners()
        
        // SOTA: Check for All Files Access permission and trigger MediaScan
        checkPermissionsAndScan()
        
        updateCalendar()
        loadRecordingsForSelectedDate()
    }
    
    /**
     * SOTA: Setup directories and load recordings.
     * Since App owns the directories, we use direct file access.
     */
    private fun checkPermissionsAndScan() {
        // SOTA: UI App creates directories so it OWNS them (can listFiles)
        setupStorageDirectories()
        
        // Direct file access - no MediaStore needed
        RecordingScanner.invalidateCache()
        updateCalendar()
        loadRecordingsForSelectedDate()
    }
    
    /**
     * SOTA: UI App creates directories so it OWNS them.
     * This ensures listFiles() works even for files created by daemon (UID 2000).
     * Directory ownership = listFiles() access, not file ownership.
     * 
     * Now uses RecordingScanner to get configured storage paths (internal or SD card).
     */
    private fun setupStorageDirectories() {
        try {
            // Get configured directories from RecordingScanner
            val recordingsDir = RecordingScanner.getRecordingsDir(requireContext())
            val surveillanceDir = RecordingScanner.getSentryEventsDir(requireContext())
            val proximityDir = RecordingScanner.getProximityEventsDir(requireContext())
            
            Log.d(TAG, "Configured directories:")
            Log.d(TAG, "  Recordings: ${recordingsDir.absolutePath}")
            Log.d(TAG, "  Surveillance: ${surveillanceDir.absolutePath}")
            Log.d(TAG, "  Proximity: ${proximityDir.absolutePath}")
            
            // Ensure base directory exists
            val baseDir = recordingsDir.parentFile
            if (baseDir != null && !baseDir.exists()) {
                val created = baseDir.mkdirs()
                Log.d(TAG, "Created base directory: ${baseDir.absolutePath} (success=$created)")
            }
            
            // Ensure subdirectories exist
            listOf(recordingsDir, surveillanceDir, proximityDir).forEach { dir ->
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    Log.d(TAG, "Created subdirectory: ${dir.absolutePath} (success=$created)")
                }
            }
            
            // Verify we can list files now
            val files = recordingsDir.listFiles()
            Log.d(TAG, "After setup - recordings dir listFiles: ${files?.size ?: "null"}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup storage directories: ${e.message}")
        }
    }
    
    private fun initViews(view: View) {
        tvCurrentMonth = view.findViewById(R.id.tvCurrentMonth)
        btnPrevMonth = view.findViewById(R.id.btnPrevMonth)
        btnNextMonth = view.findViewById(R.id.btnNextMonth)
        recyclerCalendar = view.findViewById(R.id.recyclerCalendar)
        tvSelectedDate = view.findViewById(R.id.tvSelectedDate)
        recyclerRecordings = view.findViewById(R.id.recyclerRecordings)
        tvEmptyState = view.findViewById(R.id.tvEmptyState)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        
        // Multi-select toolbar
        selectToolbar = view.findViewById(R.id.selectToolbar)
        tvSelectedCount = view.findViewById(R.id.tvSelectedCount)
        btnSelectAll = view.findViewById(R.id.btnSelectAll)
        btnDeleteSelected = view.findViewById(R.id.btnDeleteSelected)
        btnCancelSelect = view.findViewById(R.id.btnCancelSelect)
        
        btnSelectAll?.setOnClickListener { recordingAdapter.selectAll() }
        btnDeleteSelected?.setOnClickListener { confirmBatchDelete() }
        btnCancelSelect?.setOnClickListener { exitSelectMode() }
        
        // Filter chips - modern design
        try {
            chipFilterAll = view.findViewById(R.id.btnFilterAll)
            chipFilterNormal = view.findViewById(R.id.btnFilterNormal)
            chipFilterSentry = view.findViewById(R.id.btnFilterSentry)
            chipFilterProximity = view.findViewById(R.id.btnFilterProximity)
            setupFilterChips()
        } catch (e: Exception) {
            // Filter chips not available - use default filter
        }

        // Calendar collapse + actor filter (item 7)
        try {
            calendarBody = view.findViewById(R.id.calendarBody)
            btnCalendarCollapse = view.findViewById(R.id.btnCalendarCollapse)
            btnCalendarCollapse?.setOnClickListener { toggleCalendarCollapsed() }
            // Apply initial collapsed state (SOTA: list-first, week-strip-only,
            // full month tap-to-reveal)
            calendarBody?.visibility = if (calendarCollapsed) View.GONE else View.VISIBLE
            btnCalendarCollapse?.rotation = if (calendarCollapsed) 90f else 270f

            // Always-visible week strip
            recyclerWeekStrip = view.findViewById(R.id.recyclerWeekStrip)
            btnPrevWeek = view.findViewById(R.id.btnPrevWeek)
            btnNextWeek = view.findViewById(R.id.btnNextWeek)
            recyclerWeekStrip?.layoutManager =
                LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            recyclerWeekStrip?.adapter = weekStripAdapter
            btnPrevWeek?.setOnClickListener { shiftWeek(-7) }
            btnNextWeek?.setOnClickListener { shiftWeek(+7) }
            // Seed the cell width from the display so the first inflate of
            // each cell has the right width — avoids the 1-2 frame "one big
            // cell stretched across the strip" flicker that would otherwise
            // appear before the post-layout width calculation runs.
            val screenWidthPx = resources.displayMetrics.widthPixels
            // Strip's available width = screen − two 32dp arrow buttons − ~8dp padding
            val arrowsPx = (32 + 32 + 8) * resources.displayMetrics.density
            val initialCell = ((screenWidthPx - arrowsPx) / 7f).toInt().coerceAtLeast(1)
            weekStripAdapter.setCellWidthPx(initialCell)

            chipActorAny      = view.findViewById(R.id.chipActorAny)
            chipSevAny        = view.findViewById(R.id.chipSevAny)
            chipActorPerson   = view.findViewById(R.id.chipActorPerson)
            chipActorVehicle  = view.findViewById(R.id.chipActorVehicle)
            chipActorBike     = view.findViewById(R.id.chipActorBike)
            chipActorAnimal   = view.findViewById(R.id.chipActorAnimal)
            chipSevAlert      = view.findViewById(R.id.chipSevAlert)
            chipSevCritical   = view.findViewById(R.id.chipSevCritical)
            setupActorChips()
        } catch (e: Exception) {
            // Older layout; ignore
        }
    }

    private fun toggleCalendarCollapsed() {
        calendarCollapsed = !calendarCollapsed
        calendarBody?.visibility = if (calendarCollapsed) View.GONE else View.VISIBLE
        btnCalendarCollapse?.rotation = if (calendarCollapsed) 90f else 270f
    }

    private fun setupActorChips() {
        // Per-row "Any" — clears only its own dimension. Severity filters are
        // unaffected when the user taps "Any" in the Actor row, and vice
        // versa; this matches the two-row visual model.
        chipActorAny?.setOnClickListener {
            actorClassFilter.clear()
            updateActorChipChecks()
            loadRecordingsForSelectedDate()
        }
        chipSevAny?.setOnClickListener {
            severityFilter.clear()
            updateActorChipChecks()
            loadRecordingsForSelectedDate()
        }
        chipActorPerson?.setOnClickListener  { toggleActorClass("person") }
        chipActorVehicle?.setOnClickListener { toggleActorClass("vehicle") }
        chipActorBike?.setOnClickListener    { toggleActorClass("bike") }
        chipActorAnimal?.setOnClickListener  { toggleActorClass("animal") }
        chipSevAlert?.setOnClickListener     { toggleSeverity("ALERT") }
        chipSevCritical?.setOnClickListener  { toggleSeverity("CRITICAL") }
        updateActorChipChecks()
    }

    private fun toggleActorClass(name: String) {
        if (actorClassFilter.contains(name)) actorClassFilter.remove(name)
        else actorClassFilter.add(name)
        updateActorChipChecks()
        loadRecordingsForSelectedDate()
    }

    private fun toggleSeverity(name: String) {
        if (severityFilter.contains(name)) severityFilter.remove(name)
        else severityFilter.add(name)
        updateActorChipChecks()
        loadRecordingsForSelectedDate()
    }

    private fun updateActorChipChecks() {
        // Per-row "Any" stays checked iff its row has no specific selections.
        chipActorAny?.isChecked      = actorClassFilter.isEmpty()
        chipSevAny?.isChecked        = severityFilter.isEmpty()
        chipActorPerson?.isChecked   = actorClassFilter.contains("person")
        chipActorVehicle?.isChecked  = actorClassFilter.contains("vehicle")
        chipActorBike?.isChecked     = actorClassFilter.contains("bike")
        chipActorAnimal?.isChecked   = actorClassFilter.contains("animal")
        chipSevAlert?.isChecked      = severityFilter.contains("ALERT")
        chipSevCritical?.isChecked   = severityFilter.contains("CRITICAL")
    }

    private fun setupFilterChips() {
        chipFilterAll?.setOnClickListener {
            currentFilter = RecordingFilter.ALL
            updateFilterChips()
            loadRecordingsForSelectedDate()
        }
        
        chipFilterNormal?.setOnClickListener {
            currentFilter = RecordingFilter.NORMAL
            updateFilterChips()
            loadRecordingsForSelectedDate()
        }
        
        chipFilterSentry?.setOnClickListener {
            currentFilter = RecordingFilter.SENTRY
            updateFilterChips()
            loadRecordingsForSelectedDate()
        }
        
        chipFilterProximity?.setOnClickListener {
            currentFilter = RecordingFilter.PROXIMITY
            updateFilterChips()
            loadRecordingsForSelectedDate()
        }
        
        updateFilterChips()
    }
    
    private fun updateFilterChips() {
        // Skip if chips not available
        if (chipFilterAll == null) return
        
        chipFilterAll?.isChecked = currentFilter == RecordingFilter.ALL
        chipFilterNormal?.isChecked = currentFilter == RecordingFilter.NORMAL
        chipFilterSentry?.isChecked = currentFilter == RecordingFilter.SENTRY
        chipFilterProximity?.isChecked = currentFilter == RecordingFilter.PROXIMITY
    }
    
    private fun setupCalendar() {
        recyclerCalendar.apply {
            layoutManager = GridLayoutManager(context, 7)
            adapter = calendarAdapter
        }
    }
    
    private fun setupRecordingsList() {
        recordingAdapter = RecordingAdapter(
            onPlay = { recording -> playRecording(recording) },
            onDelete = { recording -> confirmDelete(recording) },
            onSelectionChanged = { count -> onSelectionChanged(count) }
        )
        
        // SOTA grid (item 7): 2-column grid for video tiles. Calendar lives in
        // the collapsible header; the list takes most of the screen.
        recyclerRecordings.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = recordingAdapter
            setHasFixedSize(true)
        }
    }
    
    private fun onSelectionChanged(count: Int) {
        tvSelectedCount?.text = getString(R.string.recording_lib_selected_count, count)
        if (recordingAdapter.selectMode && selectToolbar?.visibility != View.VISIBLE) {
            selectToolbar?.visibility = View.VISIBLE
        }
    }
    
    private fun setupClickListeners() {
        btnPrevMonth.setOnClickListener {
            calendar.add(Calendar.MONTH, -1)
            selectedDay = 1
            updateCalendar()
            loadRecordingsForSelectedDate()
        }
        
        btnNextMonth.setOnClickListener {
            calendar.add(Calendar.MONTH, 1)
            selectedDay = 1
            updateCalendar()
            loadRecordingsForSelectedDate()
        }
    }
    
    private fun updateCalendar() {
        tvCurrentMonth.text = monthFormat.format(calendar.time)
        
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        
        Log.d(TAG, "Updating calendar for $year-${month+1}")
        
        // Build calendar days first (fast operation)
        val days = buildCalendarDays(year, month)
        
        // SOTA: Load recording counts in background to prevent UI lag
        if (!scanExecutor.isShutdown) {
            scanExecutor.submit {
                try {
                    val recordingCounts = RecordingScanner.getRecordingCountsByDate(requireContext(), year, month)
                    Log.d(TAG, "Recording counts for month: $recordingCounts")
                    
                    activity?.runOnUiThread {
                        if (isAdded) {
                            calendarAdapter.setDays(days, recordingCounts)
                            calendarAdapter.setSelectedDay(selectedDay)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting recording counts", e)
                }
            }
        }
        
        // Set days immediately with empty counts for instant feedback
        calendarAdapter.setDays(days, emptyMap())
        calendarAdapter.setSelectedDay(selectedDay)

        // Keep the always-visible week strip in sync with the same year/month/day.
        weekAnchor.set(year, month, selectedDay.coerceAtLeast(1))
        updateWeekStrip()
    }

    /**
     * Build a 7-day window centred on the selected date and feed it into
     * the week-strip adapter. Window starts on Sunday-of-the-selected-week so
     * weekday positions stay aligned across navigations.
     */
    private fun updateWeekStrip() {
        val rv = recyclerWeekStrip ?: return
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        // Find Sunday of the anchor's week
        val startOfWeek = (weekAnchor.clone() as Calendar).apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        }

        val days = ArrayList<CalendarAdapter.CalendarDay>(7)
        // Build counts only for days in the displayed month (cheap cache from updateCalendar).
        val anchorYear = weekAnchor.get(Calendar.YEAR)
        val anchorMonth = weekAnchor.get(Calendar.MONTH)
        val cursor = startOfWeek.clone() as Calendar
        for (i in 0 until 7) {
            val dayOfMonth = cursor.get(Calendar.DAY_OF_MONTH)
            val sameMonth = cursor.get(Calendar.YEAR) == anchorYear &&
                            cursor.get(Calendar.MONTH) == anchorMonth
            val isToday = cursor.timeInMillis == today.timeInMillis
            val isFuture = cursor.after(today)
            days.add(CalendarAdapter.CalendarDay(
                dayOfMonth = dayOfMonth,
                isCurrentMonth = sameMonth,
                isToday = isToday,
                isFuture = isFuture
            ))
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }

        weekStripAdapter.setDays(days, emptyMap())
        // Highlight the selected day if it's in this week's window AND in the
        // anchor's month (avoids highlighting a same-numbered day from an
        // adjacent month).
        weekStripAdapter.setSelectedDay(if (days.any {
            it.isCurrentMonth && it.dayOfMonth == selectedDay
        }) selectedDay else -1)

        // Force each cell to (strip_width / 7) so all 7 days fit exactly across
        // the row. Run on next layout pass once rv.width is known.
        rv.post {
            val w = rv.width
            if (w > 0) {
                weekStripAdapter.setCellWidthPx(w / 7)
            }
        }
    }

    /**
     * Shift the week-strip window by ±7 days. Crosses month boundaries and
     * pulls the parent calendar / selectedDay along so the recordings list
     * reloads for the new selected day.
     */
    private fun shiftWeek(deltaDays: Int) {
        weekAnchor.add(Calendar.DAY_OF_MONTH, deltaDays)
        // Don't allow navigating into the future. Normalise both sides to
        // midnight so the comparison is by date only — otherwise the wall-
        // clock hours/minutes inside `today` can produce inconsistent results
        // vs the normalised comparison used in updateWeekStrip().
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (weekAnchor.after(today)) {
            weekAnchor.timeInMillis = today.timeInMillis
        }
        // Sync month-level state so updateCalendar() rebuilds the right month grid
        // (in case the user expands it after this).
        calendar.set(weekAnchor.get(Calendar.YEAR),
                     weekAnchor.get(Calendar.MONTH), 1)
        selectedDay = weekAnchor.get(Calendar.DAY_OF_MONTH)
        updateCalendar()
        loadRecordingsForSelectedDate()
    }

    private fun onWeekDaySelected(day: Int) {
        // The week strip might span two months. The day from the adapter is
        // always the day-of-month of the cell; we resolve the absolute date
        // by walking the strip cells to find which one was tapped, but a
        // simpler heuristic: search forward/backward from the current anchor
        // to the closest matching day-of-month within ±3 days.
        val target = (weekAnchor.clone() as Calendar)
        // Move target to the start-of-week first so we can iterate cells
        target.set(Calendar.DAY_OF_WEEK, target.firstDayOfWeek)
        var found = false
        for (i in 0 until 7) {
            if (target.get(Calendar.DAY_OF_MONTH) == day) {
                weekAnchor.timeInMillis = target.timeInMillis
                found = true
                break
            }
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        if (!found) return
        // Don't allow tapping a future day — date-only compare (see shiftWeek).
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (weekAnchor.after(today)) return

        calendar.set(weekAnchor.get(Calendar.YEAR),
                     weekAnchor.get(Calendar.MONTH), 1)
        selectedDay = weekAnchor.get(Calendar.DAY_OF_MONTH)
        updateCalendar()
        loadRecordingsForSelectedDate()
    }
    
    private fun buildCalendarDays(year: Int, month: Int): List<CalendarAdapter.CalendarDay> {
        val days = mutableListOf<CalendarAdapter.CalendarDay>()
        
        val tempCal = Calendar.getInstance().apply {
            set(year, month, 1)
        }
        
        val today = Calendar.getInstance()
        val isCurrentMonth = today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) == month
        val isFutureMonth = (year > today.get(Calendar.YEAR)) || 
            (year == today.get(Calendar.YEAR) && month > today.get(Calendar.MONTH))
        val todayDay = if (isCurrentMonth) today.get(Calendar.DAY_OF_MONTH) else -1
        
        // Add empty cells for days before the first of the month
        val firstDayOfWeek = tempCal.get(Calendar.DAY_OF_WEEK) - 1
        repeat(firstDayOfWeek) {
            days.add(CalendarAdapter.CalendarDay(0, false))
        }
        
        // Add days of the month
        val daysInMonth = tempCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        for (day in 1..daysInMonth) {
            val isFuture = isFutureMonth || (isCurrentMonth && day > todayDay)
            days.add(CalendarAdapter.CalendarDay(
                dayOfMonth = day,
                isCurrentMonth = true,
                isToday = day == todayDay,
                isFuture = isFuture
            ))
        }
        
        return days
    }
    
    private fun onDaySelected(day: Int) {
        selectedDay = day
        calendarAdapter.setSelectedDay(day)
        // Sync the week-strip anchor so selecting a day in the month grid
        // also updates the strip's selection (and re-centers if needed).
        weekAnchor.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), day)
        updateWeekStrip()
        loadRecordingsForSelectedDate()
    }
    
    private fun loadRecordingsForSelectedDate() {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        
        // Update header immediately
        val selectedCal = Calendar.getInstance().apply {
            set(year, month, selectedDay)
        }
        tvSelectedDate.text = SimpleDateFormat("MMM d", Locale.getDefault()).format(selectedCal.time)
        
        Log.d(TAG, "Loading recordings for $year-${month+1}-$selectedDay")
        
        // SOTA: Load recordings in background to prevent UI lag during date selection
        if (scanExecutor.isShutdown) return
        scanExecutor.submit {
            try {
                // Debug: Check directories
                val recordingsDir = RecordingScanner.getRecordingsDir(requireContext())
                val sentryDir = RecordingScanner.getSentryEventsDir(requireContext())
                val proximityDir = RecordingScanner.getProximityEventsDir(requireContext())
                
                Log.d(TAG, "Recordings dir: ${recordingsDir.absolutePath}, exists: ${recordingsDir.exists()}")
                Log.d(TAG, "Sentry dir: ${sentryDir.absolutePath}, exists: ${sentryDir.exists()}")
                Log.d(TAG, "Proximity dir: ${proximityDir.absolutePath}, exists: ${proximityDir.exists()}")
                
                if (recordingsDir.exists()) {
                    val files = recordingsDir.listFiles()
                    Log.d(TAG, "Recordings dir files: ${files?.size ?: 0}")
                    files?.take(5)?.forEach { Log.d(TAG, "  - ${it.name}") }
                }
                
                val allRecordings = RecordingScanner.getRecordingsForDate(requireContext(), year, month, selectedDay)
                Log.d(TAG, "Found ${allRecordings.size} recordings for date")
                
                val typeFiltered = when (currentFilter) {
                    RecordingFilter.ALL -> allRecordings
                    RecordingFilter.NORMAL -> allRecordings.filter { it.type == RecordingFile.RecordingType.NORMAL }
                    RecordingFilter.SENTRY -> allRecordings.filter { it.type == RecordingFile.RecordingType.SENTRY }
                    RecordingFilter.PROXIMITY -> allRecordings.filter { it.type == RecordingFile.RecordingType.PROXIMITY }
                }

                // v3 actor/severity filter (item 7). Empty filters pass everything.
                val recordings = if (actorClassFilter.isEmpty() && severityFilter.isEmpty()) {
                    typeFiltered
                } else {
                    typeFiltered.filter { rec ->
                        val classOk = actorClassFilter.isEmpty()
                                || rec.actorClasses.any { it.lowercase() in actorClassFilter }
                        val sevOk = severityFilter.isEmpty()
                                || (rec.peakSeverity?.uppercase() in severityFilter)
                        classOk && sevOk
                    }
                }

                Log.d(TAG, "After filter (${currentFilter}, actor=$actorClassFilter, sev=$severityFilter): ${recordings.size} recordings")
                
                activity?.runOnUiThread {
                    if (isAdded) {
                        if (recordings.isEmpty()) {
                            recyclerRecordings.visibility = View.GONE
                            emptyStateContainer?.visibility = View.VISIBLE
                            tvEmptyState.visibility = View.VISIBLE
                            tvEmptyState.text = when (currentFilter) {
                                RecordingFilter.ALL -> "No recordings for this date"
                                RecordingFilter.NORMAL -> "No normal recordings"
                                RecordingFilter.SENTRY -> "No sentry events"
                                RecordingFilter.PROXIMITY -> "No proximity events"
                            }
                        } else {
                            recyclerRecordings.visibility = View.VISIBLE
                            emptyStateContainer?.visibility = View.GONE
                            tvEmptyState.visibility = View.GONE
                            recordingAdapter.submitList(recordings)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading recordings", e)
            }
        }
    }
    
    private fun playRecording(recording: RecordingFile) {
        try {
            val bundle = Bundle().apply {
                putString(VideoPlayerFragment.ARG_VIDEO_PATH, recording.path)
                putString(VideoPlayerFragment.ARG_VIDEO_TITLE, recording.name)
            }
            androidx.navigation.fragment.NavHostFragment.findNavController(this)
                .navigate(R.id.action_global_videoPlayer, bundle)
        } catch (e: Exception) {
            // Fallback: open with external player if navigation fails
            try {
                val uri = recording.contentUri ?: FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    recording.file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.play_with_chooser)))
            } catch (e2: Exception) {
                Toast.makeText(context, getString(R.string.toast_cannot_play_video, e2.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun confirmDelete(recording: RecordingFile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_delete_recording_title))
            .setMessage(getString(R.string.dialog_delete_recording_message, recording.name))
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.dialog_delete)) { _, _ ->
                deleteRecording(recording)
            }
            .show()
    }

    private fun deleteRecording(recording: RecordingFile) {
        if (RecordingScanner.deleteRecording(recording)) {
            Toast.makeText(context, getString(R.string.toast_recording_deleted), Toast.LENGTH_SHORT).show()
            loadRecordingsForSelectedDate()
            updateCalendar() // Refresh indicators
        } else {
            Toast.makeText(context, getString(R.string.toast_recording_delete_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmBatchDelete() {
        val selected = recordingAdapter.getSelectedRecordings()
        if (selected.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(resources.getQuantityString(R.plurals.delete_recordings_title, selected.size, selected.size))
            .setMessage(resources.getQuantityString(R.plurals.delete_recordings_message, selected.size, selected.size))
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.dialog_delete)) { _, _ ->
                batchDeleteRecordings(selected)
            }
            .show()
    }
    
    private fun batchDeleteRecordings(recordings: List<RecordingFile>) {
        if (scanExecutor.isShutdown) return
        
        scanExecutor.submit {
            var deleted = 0
            var failed = 0
            
            for (recording in recordings) {
                if (RecordingScanner.deleteRecording(recording)) {
                    deleted++
                } else {
                    failed++
                }
            }
            
            activity?.runOnUiThread {
                if (isAdded) {
                    val msg = if (failed > 0) {
                        getString(R.string.toast_batch_delete_partial, deleted, failed)
                    } else {
                        resources.getQuantityString(R.plurals.recordings_deleted_count, deleted, deleted)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    exitSelectMode()
                    loadRecordingsForSelectedDate()
                    updateCalendar()
                }
            }
        }
    }
    
    private fun exitSelectMode() {
        recordingAdapter.exitSelectMode()
        selectToolbar?.visibility = View.GONE
    }
    
    override fun onResume() {
        super.onResume()
        // Invalidate cache on resume to pick up new recordings
        RecordingScanner.invalidateCache()
        updateCalendar()
        loadRecordingsForSelectedDate()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // Shutdown executor to prevent memory leaks
        scanExecutor.shutdown()
    }
}
