package com.overdrive.app.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.ui.adapter.RecordingAdapter
import com.overdrive.app.ui.model.RecordingFile
import com.overdrive.app.ui.util.RecordingDateSelection
import com.overdrive.app.ui.util.RecordingScanner
import com.overdrive.app.ui.util.RecordingSectionHeaderDecoration
import com.overdrive.app.ui.util.RecordingLibraryFilterState
import com.overdrive.app.ui.util.RecordingsApiClient
import com.overdrive.app.ui.util.SafeKeepClipClient
import com.overdrive.app.ui.widget.AppToast
import com.overdrive.app.ui.widget.Skeleton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.overdrive.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fragment for browsing recorded videos with a slim, list-first UI.
 *
 * v4 redesign (item: SOTA recording library):
 *  - Replaced the 3-stack calendar (header + week strip + month grid) with a
 *    single tappable date row that opens a MaterialDatePicker, plus ◀/▶
 *    one-day jumps.
 *  - Replaced the always-visible Actor + Severity chip rows with a slim
 *    "Filter" pill + bottom sheet. Active filters surface as inline,
 *    individually-dismissable chips.
 *  - Added sticky time-of-day section headers via RecordingSectionHeaderDecoration.
 *  - When embedded inside RecordingsFragment (ARG_HIDE_INTERNAL_FILTERS=true),
 *    the entire filter bar collapses too — the parent's segmented control
 *    drives the source filter, and the bottom-sheet's actor/severity controls
 *    are still reachable if the parent ever flips the flag back.
 */
class RecordingLibraryFragment : Fragment() {

    companion object {
        private const val TAG = "RecordingLibrary"

        /**
         * Initial delay for warming-state poll (first retry). Doubles per
         * attempt up to {@link #WARMING_POLL_CAP_MS}.
         */
        private const val WARMING_POLL_INITIAL_MS: Long = 2_000L
        /** Hard cap so we never sleep more than 10 s between probes. */
        private const val WARMING_POLL_CAP_MS: Long = 10_000L
        /**
         * Max shift exponent — at 2 s base this still saturates the cap.
         * Prevents `Long << attempt` from rolling over on extreme attempt
         * counts (only matters if the warmup never finishes).
         */
        private const val WARMING_POLL_MAX_ATTEMPTS_FOR_BACKOFF = 8
        private const val STATE_SELECT_MODE = "recording_library.select_mode"
        private const val STATE_SELECTED_PATHS = "recording_library.selected_paths"
        private const val STATE_SELECT_ALL_PENDING = "recording_library.select_all_pending"

        /** SafeKeep pill refresh cadence while a visible clip is uploading / queued / waiting. */
        private const val SAFEKEEP_POLL_MS = 4_000L
        /** Re-read shortly after Upload now / Lock so the pill reflects the action. */
        private const val SAFEKEEP_ACTION_REFRESH_MS = 800L
        /** A ⋮ menu re-reads a clip's SafeKeep state older than this (in flight / settled), waiting at most WAIT. */
        private const val SAFEKEEP_MENU_FRESH_MS = 2_000L
        private const val SAFEKEEP_MENU_SETTLED_MS = 30_000L
        private const val SAFEKEEP_MENU_WAIT_MS = 1_000L
        /** A status check that got no answer is retried while the screen is up: quickly at first, then slower. */
        private const val SAFEKEEP_RECHECK_MS = 10_000L
        private const val SAFEKEEP_RECHECK_SLOW_MS = 30_000L
        private const val SAFEKEEP_RECHECKS = 6
        /** After Upload now, a read that still says idle can't undo "queued" for this long. */
        private const val SAFEKEEP_UPLOAD_GRACE_MS = 10_000L
        /** Rows either side of the visible window whose pills are fetched ahead of a scroll. */
        private const val SAFEKEEP_WINDOW_PAD = 20
        /** Portrait list width per column before a second column is added. */
        private const val PORTRAIT_MIN_ROW_WIDTH_DP = 340
        private const val DISABLED_ACTION_ALPHA = 0.38f

        /** When true, the fragment hides its internal filter bar — used when a
         *  parent (RecordingsFragment) drives the filter via its own segmented
         *  control. */
        const val ARG_HIDE_INTERNAL_FILTERS = "hide_internal_filters"

        /** When true, the fragment hides its internal date row (parent owns
         *  date selection). Embedded mode in [RecordingsFragment] sets both
         *  flags so the entire chrome lives in the parent header. */
        const val ARG_HIDE_INTERNAL_DATE = "hide_internal_date"

        /** Convenience factory for embedded use. */
        fun newInstanceEmbedded(): RecordingLibraryFragment =
            RecordingLibraryFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_HIDE_INTERNAL_FILTERS, true)
                    putBoolean(ARG_HIDE_INTERNAL_DATE, true)
                }
            }
    }

    // -------- Date row (may be GONE if parent owns date selection) --------
    private var tvSelectedDate: TextView? = null
    private var tvDayClipCount: TextView? = null
    private var cardDateJump: MaterialCardView? = null
    private var btnPrevDay: MaterialButton? = null
    private var btnNextDay: MaterialButton? = null
    private var dateRowContainer: View? = null

    // -------- Filter bar --------
    private var filterBar: View? = null
    private var activeFiltersGroup: ChipGroup? = null
    private var btnOpenFilters: MaterialButton? = null

    // -------- List + empty state --------
    private lateinit var recyclerRecordings: RecyclerView
    private lateinit var tvEmptyState: TextView
    private var emptyStateContainer: LinearLayout? = null
    private var initialLoadingContainer: View? = null
    private var loadStatusBar: View? = null
    private var loadStatusProgress: ProgressBar? = null
    private var tvLoadStatus: TextView? = null
    private var btnLoadRetry: View? = null
    private var btnEmptyRetry: View? = null

    // -------- Multi-select (now a docked bottom action bar) --------
    private var selectToolbar: View? = null
    private var tvSelectedCount: TextView? = null
    private var btnSelectAll: View? = null
    private var btnDeleteSelected: View? = null
    private var btnCancelSelect: View? = null
    private var btnShareSelected: View? = null
    private var pendingRestoredSelectionPaths: Set<String> = emptySet()
    private var pendingSelectAll = false
    private var allMatchingSelected = false
    private var selectionLoadInProgress = false

    // -------- Empty state CTA (filter clear) --------
    private var btnEmptyClearFilters: View? = null
    private var tvEmptyStateBody: TextView? = null

    private var appToast: AppToast? = null
    private var skeleton: Skeleton? = null

    // -------- Filter sheet (lazily inflated) --------
    private var filterSheet: BottomSheetDialog? = null
    // Refs into the inflated sheet content; null until the first open.
    private var sheetChipActorAny: Chip? = null
    private var sheetChipActorPerson: Chip? = null
    private var sheetChipActorVehicle: Chip? = null
    private var sheetChipActorBike: Chip? = null
    private var sheetChipActorAnimal: Chip? = null
    private var sheetChipSevAny: Chip? = null
    private var sheetChipSevAlert: Chip? = null
    private var sheetChipSevCritical: Chip? = null

    private lateinit var recordingAdapter: RecordingAdapter
    private var sectionHeaderDecoration: RecordingSectionHeaderDecoration? = null
    // Snapshot of currently displayed list — read by the decoration on every
    // draw pass. Single source of truth so the decoration doesn't have to
    // know about ListAdapter internals.
    private var currentList: List<RecordingFile> = emptyList()

    // Date state: we keep using a Calendar instance so the existing
    // loadRecordingsForSelectedDate() logic (year/month/selectedDay) is a
    // mechanical move from the old implementation.
    //
    // dateNarrowed = false means "show everything, sorted newest first".
    // Flipped to true the first time the user explicitly picks a date or
    // taps a prev/next-day arrow. Prevents the empty-list-on-first-load
    // symptom where the default "today" filter hid every clip captured on
    // any prior day.
    private val calendar = Calendar.getInstance()
    private var selectedDay = calendar.get(Calendar.DAY_OF_MONTH)
    private var dateNarrowed: Boolean = false
    private var currentFilter = RecordingFilter.ALL
    /**
     * Optional secondary type to include alongside [currentFilter]. Used so
     * the parent's "Dashcam" segment can request NORMAL + PROXIMITY in one
     * list — proximity-radar clips are recorded by the dashcam encoder, not
     * the surveillance one, so they belong to the Dashcam tab.
     */
    private var extraFilter: RecordingFilter? = null

    // v3 actor + severity filter state — unchanged semantics
    private val actorClassFilter = mutableSetOf<String>()  // lowercased class group names
    private val severityFilter = mutableSetOf<String>()    // "ALERT" / "CRITICAL"

    /**
     * v3 place filter — short labels (district / city) to keep. Parent
     * (RecordingsFragment) owns the toolbar chip UI; this set is the
     * authoritative state pushed in via [applyAll]. Empty = show all
     * places (and clips with no place tag pass through).
     *
     * Comparison is case-insensitive; entries stored lowercased.
     */
    private val placeFilter = mutableSetOf<String>()

    // Physical-volume filter: "INTERNAL" / "SD_CARD" / "USB". Empty = all
    // volumes (default; the daemon index already spans every storage
    // location). Set by the parent fragment's storage chips via applyAll.
    private val storageFilter = mutableSetOf<String>()

    private val dayHeaderFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    // SOTA: Background executor for scanning operations
    private var scanExecutor = Executors.newSingleThreadExecutor()
    private val incidentPackInProgress = AtomicBoolean(false)

    // -------- SafeKeep pills --------
    // One status check per screen open; while SafeKeep is off nothing else runs.
    private var safeKeepExecutor: ExecutorService? = null
    private var safeKeepActive = false
    private var safeKeepResumed = false
    private var safeKeepFetchInFlight = false
    private var safeKeepFetchQueued = false
    /** Bumped on view teardown so late background results are dropped. */
    private var safeKeepGeneration = 0
    private val safeKeepStates = HashMap<String, SafeKeepClipClient.ClipState>()
    // When each clip's SafeKeep state was last read (uptime ms); absent from a reply = no state.
    private val safeKeepReadAt = HashMap<String, Long>()
    // A newer ⋮ request supersedes a pending one; pendingMenuOpen opens it once its state is in.
    private var menuRequest = 0
    private var pendingMenuOpen: Runnable? = null
    private var menuRead: java.util.concurrent.Future<*>? = null
    private var prefetchRead: java.util.concurrent.Future<*>? = null
    // Clips with a SafeKeep action in flight: a read that raced it must not mark them fresh.
    private val safeKeepActionPending = HashMap<String, Int>()
    private val safeKeepUploadGraceUntil = HashMap<String, Long>()
    private val safeKeepPoll = Runnable {
        safeKeepPollScheduled = false
        refreshSafeKeepStates()
    }
    // Set only by the single-clip read path, so it never delays the regular poll.
    private var safeKeepPollScheduled = false

    private fun cancelSafeKeepPoll() {
        mainHandler.removeCallbacks(safeKeepPoll)
        safeKeepPollScheduled = false
    }
    private val safeKeepRecheck = Runnable { checkSafeKeep() }
    private var safeKeepRechecksLeft = 0
    private val safeKeepScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) refreshSafeKeepStates()
        }
    }

    // -----------------------------------------------------------------
    // Paging — replaces the legacy "load every row at once" path with
    // 30-row fetches against the daemon's H2-backed /api/recordings.
    // -----------------------------------------------------------------

    /**
     * Per-mount paging state. Recreated on every [resetPaging] (filter
     * change, post-delete refresh, fragment view recreate). Lives in the
     * fragment so each onCreateView starts a fresh pager — rotation drops
     * the loaded rows and re-fetches page 1 (acceptable for v1; if
     * scrollback restoration becomes a complaint we can move state into
     * onSaveInstanceState).
     */
    private inner class RecordingPagingState {
        var currentFilter: RecordingsApiClient.Filter = RecordingsApiClient.Filter()
        var page: Int = 1
        val pageSize: Int = 30
        var hasMore: Boolean = true
        var loading: Boolean = false
        var totalCount: Int = 0
        val accumulated: MutableList<RecordingFile> = mutableListOf()

        /** True once we've decided this load is fallback-mode (direct-FS). */
        var fallbackActive: Boolean = false

        /** Generation counter — bumped by resetPaging so any in-flight
         *  background result whose gen doesn't match is silently dropped. */
        var generation: Int = 0
    }

    private var pagingState: RecordingPagingState = RecordingPagingState()

    private enum class RetryTarget { FIRST_PAGE, NEXT_PAGE }
    private var retryTarget: RetryTarget? = null

    /** True after we've installed [pagingScrollListener] on the recycler. */
    private var pagingScrollListenerAttached: Boolean = false

    /** Scroll listener that drives next-page fetches. Held in a field so
     *  fallback mode can [RecyclerView.removeOnScrollListener] cleanly. */
    private val pagingScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0) return  // only react to forward scrolls
            val lm = rv.layoutManager as? GridLayoutManager ?: return
            val total = lm.itemCount
            val lastVisible = lm.findLastVisibleItemPosition()
            if (lastVisible == RecyclerView.NO_POSITION) return
            if (lastVisible >= total - 5
                && !pagingState.loading
                && pagingState.hasMore
                && !pagingState.fallbackActive) {
                loadNextPage()
            }
        }
    }

    /** Main-thread handler for warming retries. Never holds the fragment
     *  past onDestroyView — every posted runnable rechecks isAdded/view. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Pending warmup-poll runnable so onDestroyView can cancel it. */
    private var warmupPollRunnable: Runnable? = null

    /**
     * Exponential-backoff counter for the warming-state poll. Resets to
     * zero whenever loadFirstPage runs to a non-warming response. Each
     * successive warming response doubles the next poll delay up to the
     * cap below — protects the daemon from a 60+ s warmup hammering the
     * HTTP server with one request every 1.5 s on every connected client.
     */
    private var warmupRetryAttempt: Int = 0

    /**
     * True while the shared warmup backoff counter has been advanced by the
     * index-down poller rather than a real warmup. Lets the next successful
     * response reset the counter so a later warmup starts at the 2 s base
     * instead of inheriting the index-down poller's 10 s cap.
     */
    private var indexDownBackoffUsed: Boolean = false

    enum class RecordingFilter {
        ALL, NORMAL, SENTRY, PROXIMITY, REPLAY
    }

    /**
     * Optional override for tap-to-play behavior. When non-null,
     * [playRecording] invokes this callback INSTEAD of launching the
     * global full-screen [VideoPlayerFragment] via nav action.
     */
    var onPlayRecording: ((RecordingFile) -> Unit)? = null

    /**
     * Fires every time the visible recording list changes (after filters /
     * date apply). Used by [RecordingsFragment] to keep the inline player's
     * prev/next playlist in sync with what the user actually sees.
     */
    var onListChanged: ((List<RecordingFile>) -> Unit)? = null

    /**
     * Fires only when the underlying file set CHANGES (delete / batch
     * delete / external rescan). Distinct from
     * [onListChanged] which fires on every filter pass / re-render — the
     * parent fragment uses this to refresh its chip-derivation scan
     * (which walks every directory) WITHOUT re-walking on every chip
     * click.
     */
    var onContentChanged: (() -> Unit)? = null

    /** Draws the selection bar outside this fragment; [selectToolbar] stays hidden while one is set. */
    fun interface SelectionHost {
        fun onSelectionChromeChanged(active: Boolean, count: Int, actionsEnabled: Boolean)
    }

    var selectionHost: SelectionHost? = null
        set(value) {
            field = value
            renderSelectionChrome()
        }

    fun selectAllFromHost() = selectAllRecordings()

    fun shareSelectionFromHost() = shareSelectedRecordings()

    fun deleteSelectionFromHost() = confirmBatchDelete()

    fun exitSelectionFromHost() = exitSelectMode()

    /** Selection mode is on, whichever selection chrome (landscape bar or portrait) shows it. */
    fun isSelectingFromHost(): Boolean = runCatching { recordingAdapter.selectMode }.getOrDefault(false)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_recording_library, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Single source of executor lifecycle: created here when needed,
        // shut down in onDestroyView. The previous version also rebuilt it
        // in onCreate which created a race when onCreate / onViewCreated
        // ran out-of-expected-order on configuration change.
        if (scanExecutor.isShutdown || scanExecutor.isTerminated) {
            scanExecutor = Executors.newSingleThreadExecutor()
        }
        safeKeepExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "RecordingsSafeKeep").apply { isDaemon = true }
        }

        initViews(view)
        appToast = AppToast(view)
        skeleton = Skeleton(view).apply { follow(R.id.initialLoadingContainer) }
        setupRecordingsList()
        restoreSelectionState(savedInstanceState)
        setupClickListeners()

        // When the parent drives filters via its own segmented control, also
        // hide the in-page filter bar. Actor + severity stay reachable via
        // setSourceFilter() and (if the parent ever surfaces it) the bottom
        // sheet — but visually the user sees just the date row and the list.
        if (arguments?.getBoolean(ARG_HIDE_INTERNAL_FILTERS, false) == true) {
            filterBar?.visibility = View.GONE
        }
        // Embedded mode: parent owns the date row too.
        if (arguments?.getBoolean(ARG_HIDE_INTERNAL_DATE, false) == true) {
            dateRowContainer?.visibility = View.GONE
        }

        setupStorageDirectories()

        // No load here: standalone mode loads in onResume, embedded mode via the
        // parent's applyAllFiltersTo → applyAll. Loading here made both modes
        // fetch twice, and embedded used this fragment's stale date state.
        updateDateHeader()
        renderActiveFilters()
    }

    private fun restoreSelectionState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        val selectMode = savedInstanceState.getBoolean(STATE_SELECT_MODE, false)
        pendingSelectAll = selectMode &&
            savedInstanceState.getBoolean(STATE_SELECT_ALL_PENDING, false)
        allMatchingSelected = false
        pendingRestoredSelectionPaths = if (selectMode) {
            savedInstanceState.getStringArrayList(STATE_SELECTED_PATHS)
                .orEmpty()
                .filter(String::isNotEmpty)
                .toSet()
        } else {
            emptySet()
        }
        recordingAdapter.restoreSelection(
            selectMode = selectMode,
            selectedPaths = emptySet()
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::recordingAdapter.isInitialized) {
            outState.putBoolean(STATE_SELECT_MODE, recordingAdapter.selectMode)
            val restoreAll = pendingSelectAll || allMatchingSelected
            outState.putBoolean(STATE_SELECT_ALL_PENDING, restoreAll)
            val selectedPaths = when {
                restoreAll -> emptySet()
                pendingRestoredSelectionPaths.isNotEmpty() ->
                    pendingRestoredSelectionPaths
                else -> recordingAdapter.getSelectedPaths()
            }
            outState.putStringArrayList(
                STATE_SELECTED_PATHS,
                ArrayList(selectedPaths)
            )
        }
        super.onSaveInstanceState(outState)
    }

    private fun setupStorageDirectories() {
        try {
            val recordingsDir = RecordingScanner.getRecordingsDir(requireContext())
            val surveillanceDir = RecordingScanner.getSentryEventsDir(requireContext())
            val proximityDir = RecordingScanner.getProximityEventsDir(requireContext())

            Log.d(TAG, "Configured directories:")
            Log.d(TAG, "  Recordings: ${recordingsDir.absolutePath}")
            Log.d(TAG, "  Surveillance: ${surveillanceDir.absolutePath}")
            Log.d(TAG, "  Proximity: ${proximityDir.absolutePath}")

            val baseDir = recordingsDir.parentFile
            if (baseDir != null && !baseDir.exists()) {
                val created = baseDir.mkdirs()
                Log.d(TAG, "Created base directory: ${baseDir.absolutePath} (success=$created)")
            }

            listOf(recordingsDir, surveillanceDir, proximityDir).forEach { dir ->
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    Log.d(TAG, "Created subdirectory: ${dir.absolutePath} (success=$created)")
                }
            }

            val files = recordingsDir.listFiles()
            Log.d(TAG, "After setup - recordings dir listFiles: ${files?.size ?: "null"}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup storage directories: ${e.message}")
        }
    }

    private fun initViews(view: View) {
        // Date row — present in standalone layouts only.
        tvSelectedDate = view.findViewById(R.id.tvSelectedDate)
        tvDayClipCount = view.findViewById(R.id.tvDayClipCount)
        cardDateJump = view.findViewById(R.id.cardDateJump)
        btnPrevDay = view.findViewById(R.id.btnPrevDay)
        btnNextDay = view.findViewById(R.id.btnNextDay)
        dateRowContainer = view.findViewById(R.id.dateRowContainer)

        // Filter bar
        filterBar = view.findViewById(R.id.filterBar)
        activeFiltersGroup = view.findViewById(R.id.activeFiltersGroup)
        btnOpenFilters = view.findViewById(R.id.btnOpenFilters)

        // List
        recyclerRecordings = view.findViewById(R.id.recyclerRecordings)
        tvEmptyState = view.findViewById(R.id.tvEmptyState)
        tvEmptyStateBody = view.findViewById(R.id.tvEmptyStateBody)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
        btnEmptyClearFilters = view.findViewById(R.id.btnEmptyClearFilters)
        initialLoadingContainer = view.findViewById(R.id.initialLoadingContainer)
        loadStatusBar = view.findViewById(R.id.loadStatusBar)
        loadStatusProgress = view.findViewById(R.id.loadStatusProgress)
        tvLoadStatus = view.findViewById(R.id.tvLoadStatus)
        btnLoadRetry = view.findViewById(R.id.btnLoadRetry)
        btnEmptyRetry = view.findViewById(R.id.btnEmptyRetry)

        // Multi-select bottom action bar
        selectToolbar = view.findViewById(R.id.selectToolbar)
        tvSelectedCount = view.findViewById(R.id.tvSelectedCount)
        btnSelectAll = view.findViewById(R.id.btnSelectAll)
        btnDeleteSelected = view.findViewById(R.id.btnDeleteSelected)
        btnCancelSelect = view.findViewById(R.id.btnCancelSelect)
        btnShareSelected = view.findViewById(R.id.btnShareSelected)

        btnSelectAll?.setOnClickListener { selectAllRecordings() }
        btnDeleteSelected?.setOnClickListener { confirmBatchDelete() }
        btnCancelSelect?.setOnClickListener { exitSelectMode() }
        btnShareSelected?.setOnClickListener { shareSelectedRecordings() }

        // Empty-state CTA: only meaningful when filters are narrowing the
        // visible set. The parent owns chip/place/date state so we proxy
        // via [onClearAllFiltersRequested]; standalone callers (no parent)
        // get an in-fragment fallback that drops actor/severity/date.
        btnEmptyClearFilters?.setOnClickListener { handleClearFiltersRequested() }
        btnLoadRetry?.setOnClickListener { retryFailedLoad() }
        btnEmptyRetry?.setOnClickListener { retryFailedLoad() }
    }

    /**
     * Optional: when the user taps the empty-state "Clear filters" CTA,
     * the host fragment is notified so it can drop its own filters
     * (segment/date/place chips) — this fragment's local actor/severity
     * are cleared either way.
     */
    var onClearAllFiltersRequested: (() -> Unit)? = null

    private fun handleClearFiltersRequested() {
        // Always clear local state — even if a parent handler exists,
        // the parent's clearAll covers actor/severity by re-pushing
        // empty sets on the next applyAll. The local clear keeps the
        // standalone case correct and avoids a redundant reload.
        actorClassFilter.clear()
        severityFilter.clear()
        placeFilter.clear()
        storageFilter.clear()
        placeContainsQuery = ""
        parkingSessionIdFilter = ""
        // Drop date narrowing too — the user's intent is "show me anything".
        dateNarrowed = false
        renderActiveFilters()
        onClearAllFiltersRequested?.invoke()
        // Standalone callers (no parent) need a manual reload; embedded
        // mode reloads via the parent's applyAll() chain.
        if (arguments?.getBoolean(ARG_HIDE_INTERNAL_FILTERS, false) != true) {
            updateDateHeader()
            loadRecordingsForSelectedDate()
        }
    }

    /**
     * Atomically apply every filter dimension AND trigger one reload.
     *
     * This is the single API the parent ([RecordingsFragment]) uses for
     * segment / date / chip changes. Before this consolidation we had four
     * separate setters; calling them in sequence after a segment switch
     * triggered three or four overlapping reloads on the single-thread
     * scan executor and races between them produced stale lists. One
     * atomic call = one reload, with all state already in place.
     *
     * Safe to call before [onViewCreated] (e.g. immediately after a child
     * fragment commit). State is captured into fields synchronously; the
     * subsequent [onViewCreated] runs its own load with the captured state.
     */
    /**
     * v3 free-text place search. Passed through from the parent's
     * search-box debounce; merged into the API filter as `placeContains`.
     * Stacks with `placeFilter`: both must match server-side.
     */
    private var placeContainsQuery: String = ""

    /**
     * Parking Intelligence: narrow to the clips stamped with one parking
     * session (arrived from a park's "Open events"). Server-side only — the
     * direct-filesystem fallback has no sidecar index and ignores it.
     */
    private var parkingSessionIdFilter: String = ""

    fun applyAll(
        source: RecordingFilter,
        actorClasses: Set<String>,
        severity: Set<String>,
        year: Int,
        month: Int,
        day: Int,
        extraSource: RecordingFilter? = null,
        narrowToDate: Boolean = false,
        places: Set<String> = emptySet(),
        placeContains: String? = null,
        storages: Set<String> = emptySet(),
        parkingSessionId: String? = null
    ) {
        currentFilter = source
        extraFilter = extraSource
        actorClassFilter.clear()
        actorClassFilter.addAll(actorClasses.map { it.lowercase() })
        severityFilter.clear()
        severityFilter.addAll(severity.map { it.uppercase() })
        placeFilter.clear()
        places.asSequence()
            .map { it.trim().lowercase() }
            .firstOrNull { it.isNotEmpty() }
            ?.let(placeFilter::add)
        storageFilter.clear()
        storageFilter.addAll(storages.map { it.uppercase() })
        placeContainsQuery = placeContains?.trim()?.lowercase() ?: ""
        parkingSessionIdFilter = parkingSessionId?.trim() ?: ""
        calendar.set(year, month, 1)
        selectedDay = day
        // Caller decides whether to narrow. The parent flips this true only
        // when the user explicitly picks a date or taps prev/next-day; the
        // initial mount call passes false so the user sees ALL recordings,
        // not just clips that happen to share today's date.
        dateNarrowed = narrowToDate

        if (view != null && ::recordingAdapter.isInitialized) {
            updateDateHeader()
            renderActiveFilters()
            loadRecordingsForSelectedDate()
        }
    }

    // -----------------------------------------------------------------
    // Filter sheet
    // -----------------------------------------------------------------

    private fun openFilterSheet() {
        // Dismiss any leftover sheet from a previous open (e.g. config change).
        filterSheet?.dismiss()
        val ctx = context ?: return
        val sheet = BottomSheetDialog(ctx, R.style.Theme_Overdrive_M3_BottomSheet)
        val sheetView = LayoutInflater.from(ctx)
            .inflate(R.layout.sheet_recording_library_filters, null, false)
        // M3 sheets are edge-to-edge: without this the Apply button sits
        // behind the system navigation bar on the head unit.
        com.overdrive.app.ui.util.SheetInsets.padForNavigationBar(sheetView)
        sheet.setContentView(sheetView)
        // Standalone library mode only owns actor/severity filters. The host
        // RecordingsFragment owns type/place/storage and wires those sections
        // when it opens the same recording-specific sheet.
        sheetView.findViewById<View>(R.id.sheetTypeSection)?.visibility = View.GONE
        sheetView.findViewById<View>(R.id.sheetPlaceSection)?.visibility = View.GONE
        sheetView.findViewById<View>(R.id.sheetStorageSection)?.visibility = View.GONE

        sheetChipActorAny      = sheetView.findViewById(R.id.chipActorAny)
        sheetChipActorPerson   = sheetView.findViewById(R.id.chipActorPerson)
        sheetChipActorVehicle  = sheetView.findViewById(R.id.chipActorVehicle)
        sheetChipActorBike     = sheetView.findViewById(R.id.chipActorBike)
        sheetChipActorAnimal   = sheetView.findViewById(R.id.chipActorAnimal)
        sheetChipSevAny        = sheetView.findViewById(R.id.chipSevAny)
        sheetChipSevAlert      = sheetView.findViewById(R.id.chipSevAlert)
        sheetChipSevCritical   = sheetView.findViewById(R.id.chipSevCritical)

        // Per-row "Any" — clears that row only. Same semantics as before.
        sheetChipActorAny?.setOnClickListener {
            actorClassFilter.clear()
            syncSheetChipChecks()
        }
        sheetChipSevAny?.setOnClickListener {
            severityFilter.clear()
            syncSheetChipChecks()
        }
        sheetChipActorPerson?.setOnClickListener  { toggleActorClass("person") }
        sheetChipActorVehicle?.setOnClickListener { toggleActorClass("vehicle") }
        sheetChipActorBike?.setOnClickListener    { toggleActorClass("bike") }
        sheetChipActorAnimal?.setOnClickListener  { toggleActorClass("animal") }
        sheetChipSevAlert?.setOnClickListener     { toggleSeverity("ALERT") }
        sheetChipSevCritical?.setOnClickListener  { toggleSeverity("CRITICAL") }

        val btnReset = sheetView.findViewById<MaterialButton>(R.id.btnFilterReset)
        btnReset.setOnClickListener {
            actorClassFilter.clear()
            severityFilter.clear()
            syncSheetChipChecks()
        }

        val btnApply = sheetView.findViewById<MaterialButton>(R.id.btnFilterApply)
        btnApply.setOnClickListener {
            sheet.dismiss()
            renderActiveFilters()
            loadRecordingsForSelectedDate()
        }
        sheetView.findViewById<View>(R.id.btnFilterCancel)?.setOnClickListener { sheet.dismiss() }

        sheet.setOnDismissListener {
            // Clear refs so a stale view from a now-destroyed sheet doesn't
            // get touched on the next state change.
            sheetChipActorAny = null
            sheetChipActorPerson = null
            sheetChipActorVehicle = null
            sheetChipActorBike = null
            sheetChipActorAnimal = null
            sheetChipSevAny = null
            sheetChipSevAlert = null
            sheetChipSevCritical = null
            // Re-render bar from the latest filter state (apply already did
            // this; this is the cancel/dismiss-by-back path).
            renderActiveFilters()
            loadRecordingsForSelectedDate()
            filterSheet = null
        }

        syncSheetChipChecks()
        filterSheet = sheet
        sheet.show()
    }

    private fun toggleActorClass(name: String) {
        if (actorClassFilter.contains(name)) actorClassFilter.remove(name)
        else actorClassFilter.add(name)
        syncSheetChipChecks()
    }

    private fun toggleSeverity(name: String) {
        if (severityFilter.contains(name)) severityFilter.remove(name)
        else severityFilter.add(name)
        syncSheetChipChecks()
    }

    private fun syncSheetChipChecks() {
        sheetChipActorAny?.isChecked      = actorClassFilter.isEmpty()
        sheetChipSevAny?.isChecked        = severityFilter.isEmpty()
        sheetChipActorPerson?.isChecked   = actorClassFilter.contains("person")
        sheetChipActorVehicle?.isChecked  = actorClassFilter.contains("vehicle")
        sheetChipActorBike?.isChecked     = actorClassFilter.contains("bike")
        sheetChipActorAnimal?.isChecked   = actorClassFilter.contains("animal")
        sheetChipSevAlert?.isChecked      = severityFilter.contains("ALERT")
        sheetChipSevCritical?.isChecked   = severityFilter.contains("CRITICAL")
    }

    /**
     * Chips created in code can't take a style attribute, so the app's
     * component corner size has to be applied here.
     */
    private fun Chip.applyAppCornerShape() {
        shapeAppearanceModel = shapeAppearanceModel
            .withCornerSize(resources.getDimension(R.dimen.card_radius_accent))
    }

    /**
     * Rebuild the inline active-filter chips and update the trailing pill's
     * label/badge to reflect the current filter state.
     */
    private fun renderActiveFilters() {
        val group = activeFiltersGroup ?: return
        val ctx = context ?: return

        group.removeAllViews()

        val active = mutableListOf<Pair<String, () -> Unit>>()
        if (actorClassFilter.contains("person"))
            active += getString(R.string.recording_lib_chip_person) to {
                actorClassFilter.remove("person")
            }
        if (actorClassFilter.contains("vehicle"))
            active += getString(R.string.recording_lib_chip_vehicle) to {
                actorClassFilter.remove("vehicle")
            }
        if (actorClassFilter.contains("bike"))
            active += getString(R.string.recording_lib_chip_bike) to {
                actorClassFilter.remove("bike")
            }
        if (actorClassFilter.contains("animal"))
            active += getString(R.string.recording_lib_chip_animal) to {
                actorClassFilter.remove("animal")
            }
        if (severityFilter.contains("ALERT"))
            active += getString(R.string.recording_lib_chip_alert) to {
                severityFilter.remove("ALERT")
            }
        if (severityFilter.contains("CRITICAL"))
            active += getString(R.string.recording_lib_chip_critical) to {
                severityFilter.remove("CRITICAL")
            }

        for ((label, removeAction) in active) {
            val chip = Chip(ctx).apply {
                applyAppCornerShape()
                setEnsureMinTouchTargetSize(false)
                text = label
                isCloseIconVisible = true
                isCheckable = false
                isClickable = true
                contentDescription = getString(R.string.cd_clear_filter)
                setOnCloseIconClickListener {
                    removeAction()
                    renderActiveFilters()
                    loadRecordingsForSelectedDate()
                }
            }
            group.addView(chip)
        }

        // Pill label updates with active count (n>0 → "Filter · 2", else "Filter")
        btnOpenFilters?.text = if (active.isEmpty()) {
            getString(R.string.recording_lib_filter_button)
        } else {
            getString(R.string.recording_lib_filter_button_active, active.size)
        }
    }

    // -----------------------------------------------------------------
    // Date handling
    // -----------------------------------------------------------------

    private fun setupClickListeners() {
        cardDateJump?.setOnClickListener { showDatePicker() }
        btnPrevDay?.setOnClickListener { shiftSelectedDay(-1) }
        btnNextDay?.setOnClickListener { shiftSelectedDay(+1) }
        btnOpenFilters?.setOnClickListener { openFilterSheet() }
    }

    private fun showDatePicker() {
        RecordingDateSelection.showPicker(parentFragmentManager, calendar, selectedDay, ::selectDate)
    }

    private fun shiftSelectedDay(delta: Int) {
        val day = RecordingDateSelection.shiftDay(calendar, selectedDay, delta) ?: return
        selectDate(day)
    }

    private fun selectDate(day: Calendar) {
        calendar.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), 1)
        selectedDay = day.get(Calendar.DAY_OF_MONTH)
        dateNarrowed = true
        updateDateHeader()
        loadRecordingsForSelectedDate()
    }

    private fun updateDateHeader() {
        val header = RecordingDateSelection.dayHeader(calendar, selectedDay)
        tvSelectedDate?.text = header.selectionLabel(dayHeaderFormat, ::getString)

        // Disable forward-day button when we're already on today.
        btnNextDay?.let {
            it.isEnabled = header.canAdvance
            it.alpha = if (it.isEnabled) 1f else 0.4f
        }
    }

    // -----------------------------------------------------------------
    // List
    // -----------------------------------------------------------------

    private fun setupRecordingsList() {
        val landscape =
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        recordingAdapter = RecordingAdapter(
            onPlay = { recording -> playRecording(recording) },
            onDelete = { recording -> confirmDelete(recording) },
            onSelectionChanged = { count -> onSelectionChanged(count) },
            onShare = { recording -> shareSingleRecording(recording) },
            onEvidencePack = { recording -> confirmCreateIncidentPack(recording) },
            landscapeRows = landscape,
            onSafeKeepAction = { recording, action -> onSafeKeepAction(recording, action) }
        )
        recordingAdapter.actionsMenuGate = { anchor, recording, show ->
            openActionsMenu(anchor, recording, { true }, show)
        }

        val gridLayoutManager = GridLayoutManager(context, 1)
        recyclerRecordings.apply {
            layoutManager = gridLayoutManager
            adapter = recordingAdapter
            setHasFixedSize(true)
        }
        if (!landscape) {
            // Portrait rows match landscape: one column below ~2 row widths.
            recyclerRecordings.doOnLayout { list ->
                val minRowWidth = (PORTRAIT_MIN_ROW_WIDTH_DP * resources.displayMetrics.density).toInt()
                val available = list.width - list.paddingStart - list.paddingEnd
                gridLayoutManager.spanCount = (available / minRowWidth).coerceIn(1, 2)
            }
        }

        // Sticky time-of-day section headers. Decoration reads `currentList`
        // every frame so it stays in sync without us re-attaching it on every
        // submitList().
        sectionHeaderDecoration?.let { recyclerRecordings.removeItemDecoration(it) }
        val deco = RecordingSectionHeaderDecoration(requireContext()) { currentList }
        recyclerRecordings.addItemDecoration(deco)
        sectionHeaderDecoration = deco

        // Attach the paging scroll listener once. Removed in onDestroyView /
        // when fallback mode kicks in (direct-FS path is non-paged so the
        // listener would just no-op).
        if (!pagingScrollListenerAttached) {
            recyclerRecordings.addOnScrollListener(pagingScrollListener)
            pagingScrollListenerAttached = true
        }
        recyclerRecordings.removeOnScrollListener(safeKeepScrollListener)
        recyclerRecordings.addOnScrollListener(safeKeepScrollListener)
    }

    private fun onSelectionChanged(count: Int) {
        if (!selectionLoadInProgress) {
            allMatchingSelected = count > 0 &&
                !pagingState.hasMore &&
                count == currentList.size
        }
        tvSelectedCount?.text = getString(R.string.recording_lib_selected_count, count)
        renderSelectionChrome()
    }

    /** Shows the selection bar while in select mode: [selectionHost] when set, else [selectToolbar]. */
    private fun renderSelectionChrome() {
        if (view == null || !::recordingAdapter.isInitialized) return
        val active = recordingAdapter.selectMode
        val host = selectionHost
        if (host != null) {
            selectToolbar?.visibility = View.GONE
            host.onSelectionChromeChanged(active, recordingAdapter.selectedCount, !selectionLoadInProgress)
        } else {
            selectToolbar?.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    /**
     * Full actions menu for [recording] (share, evidence pack, SafeKeep, delete), from the preview pane.
     * [stillCurrent] is checked again before a menu that waited for SafeKeep state opens.
     */
    fun showRecordingActions(anchor: View, recording: RecordingFile, stillCurrent: () -> Boolean = { true }) {
        if (!::recordingAdapter.isInitialized) return
        openActionsMenu(anchor, recording, stillCurrent) { recordingAdapter.showActionsMenu(anchor, recording) }
    }

    /**
     * Opens a ⋮ menu with fresh SafeKeep items: a state that is not fresh (isSafeKeepStateFresh) is re-read
     * first, waiting at most SAFEKEEP_MENU_WAIT_MS before opening with what is cached.
     */
    private fun openActionsMenu(
        anchor: View,
        recording: RecordingFile,
        stillCurrent: () -> Boolean,
        show: () -> Unit,
    ) {
        cancelPendingMenu()
        val request = menuRequest
        val name = recording.file.name
        val executor = safeKeepExecutor?.takeUnless { it.isShutdown }
        if (!safeKeepActive || isSafeKeepStateFresh(name) || executor == null) {
            show()
            return
        }
        val gen = safeKeepGeneration
        val open = Runnable {
            if (request != menuRequest) return@Runnable
            menuRequest++
            pendingMenuOpen = null
            if (gen == safeKeepGeneration && view != null && isResumed &&
                anchor.isAttachedToWindow && stillCurrent()
            ) {
                show()
            }
        }
        pendingMenuOpen = open
        mainHandler.postDelayed(open, SAFEKEEP_MENU_WAIT_MS)
        menuRead = executor.submit {
            val result = SafeKeepClipClient.clipStates(listOf(name))
            mainHandler.post {
                if (gen != safeKeepGeneration || view == null) return@post
                applyClipState(name, result)
                if (request == menuRequest) {
                    mainHandler.removeCallbacks(open)
                    open.run()
                }
            }
        }
    }

    /** A newer request, a pause or teardown drops a waiting menu and its queued state read. */
    private fun cancelPendingMenu() {
        menuRequest++
        pendingMenuOpen?.let { mainHandler.removeCallbacks(it) }
        pendingMenuOpen = null
        menuRead?.cancel(false)
        menuRead = null
    }

    /** An in-flight state is fresh for SAFEKEEP_MENU_FRESH_MS, a settled one for SAFEKEEP_MENU_SETTLED_MS. */
    private fun isSafeKeepStateFresh(name: String): Boolean {
        val readAt = safeKeepReadAt[name] ?: return false
        val limit = if (safeKeepStates[name]?.inFlight == true) SAFEKEEP_MENU_FRESH_MS else SAFEKEEP_MENU_SETTLED_MS
        return SystemClock.uptimeMillis() - readAt < limit
    }

    private fun applyClipState(name: String, result: SafeKeepClipClient.StatesResult?) {
        when {
            result?.disabled == true -> {
                safeKeepActive = false
                clearSafeKeep()
            }
            result != null && safeKeepActive -> {
                mergeClipStates(listOf(name), result.states)
                recordingAdapter.setSafeKeepStates(true, HashMap(safeKeepStates))
                // An upload found by this read keeps the pills polling like one found by a batch,
                // without pushing back a poll that is already due.
                if (safeKeepStates[name]?.inFlight == true && safeKeepResumed && !safeKeepPollScheduled) {
                    mainHandler.postDelayed(safeKeepPoll, SAFEKEEP_POLL_MS)
                    safeKeepPollScheduled = true
                }
            }
        }
    }

    /** Folds a reply for [names] into the cache; a clip inside its upload grace keeps its "queued". */
    private fun mergeClipStates(names: Collection<String>, states: Map<String, SafeKeepClipClient.ClipState>) {
        val now = SystemClock.uptimeMillis()
        for (name in names) {
            // A reply sent before a pending action reached the daemon must not undo its optimistic state.
            if (safeKeepActionPending.containsKey(name)) continue
            val server = states[name]
            val settledByServer = server != null && (server.inFlight || server.state == "saved")
            val graceUntil = safeKeepUploadGraceUntil[name]
            if (graceUntil != null) {
                if (now < graceUntil && !settledByServer) continue
                safeKeepUploadGraceUntil.remove(name)
            }
            if (server == null) safeKeepStates.remove(name) else safeKeepStates[name] = server
            safeKeepReadAt[name] = now
        }
    }

    /** Reads [recording]'s SafeKeep state ahead of its ⋮ (the previewed clip can be outside the fetched window). */
    fun prefetchSafeKeepState(recording: RecordingFile) {
        if (!::recordingAdapter.isInitialized || !safeKeepActive || !safeKeepResumed) return
        val name = recording.file.name
        if (isSafeKeepStateFresh(name)) return
        val executor = safeKeepExecutor?.takeUnless { it.isShutdown } ?: return
        val gen = safeKeepGeneration
        // Only the latest previewed clip matters: drop a prefetch still waiting in the queue.
        prefetchRead?.cancel(false)
        prefetchRead = executor.submit {
            val result = SafeKeepClipClient.clipStates(listOf(name))
            mainHandler.post {
                if (gen == safeKeepGeneration && view != null) applyClipState(name, result)
            }
        }
    }

    private fun selectAllRecordings() {
        if (!::recordingAdapter.isInitialized ||
            !recordingAdapter.selectMode ||
            recordingAdapter.itemCount == 0 ||
            selectionLoadInProgress ||
            pagingState.loading
        ) {
            return
        }
        val allRowsLoaded = pagingState.fallbackActive ||
            !pagingState.hasMore ||
            pagingState.accumulated.size >= pagingState.totalCount
        if (allRowsLoaded) {
            pendingSelectAll = false
            recordingAdapter.selectAllLoaded()
            return
        }
        loadAllForSelection(restoredPaths = null)
    }

    private fun resolveRestoredSelectionIfNeeded() {
        val requested = pendingRestoredSelectionPaths
        if (requested.isEmpty() || !recordingAdapter.selectMode) {
            pendingRestoredSelectionPaths = emptySet()
            return
        }

        val loadedPaths = currentList.mapTo(mutableSetOf()) { it.path }
        if (loadedPaths.containsAll(requested)) {
            pendingRestoredSelectionPaths = emptySet()
            recordingAdapter.restoreSelection(true, requested)
            return
        }

        if (pagingState.fallbackActive || !pagingState.hasMore) {
            pendingRestoredSelectionPaths = emptySet()
            recordingAdapter.restoreSelection(true, requested.intersect(loadedPaths))
            return
        }
        loadAllForSelection(restoredPaths = requested)
    }

    /**
     * Selection is the only workflow that needs a complete result set.
     * Normal browsing remains paged; "All" and restored off-page selections
     * resolve every matching row before batch actions are enabled.
     */
    private fun loadAllForSelection(restoredPaths: Set<String>?) {
        if (selectionLoadInProgress || scanExecutor.isShutdown) return

        pendingSelectAll = restoredPaths == null
        selectionLoadInProgress = true
        setSelectionActionsEnabled(false)
        showLoadStatus(
            message = getString(R.string.recording_library_loading_more),
            showProgress = true,
            retry = null
        )
        val generation = pagingState.generation
        val filter = pagingState.currentFilter

        scanExecutor.submit {
            val all = try {
                RecordingsApiClient.fetchAllRecordings(filter)
            } catch (t: Throwable) {
                Log.w(TAG, "Selection expansion failed: ${t.message}")
                null
            }

            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                if (generation != pagingState.generation) return@runOnUiThread
                if (!recordingAdapter.selectMode) {
                    pendingRestoredSelectionPaths = emptySet()
                    pendingSelectAll = false
                    allMatchingSelected = false
                    finishSelectionLoad(showError = false)
                    return@runOnUiThread
                }

                if (all == null ||
                    (all.isEmpty() && pagingState.totalCount > 0)
                ) {
                    if (restoredPaths != null) {
                        val loadedPaths = currentList.mapTo(mutableSetOf()) { it.path }
                        pendingRestoredSelectionPaths = emptySet()
                        recordingAdapter.restoreSelection(
                            true,
                            restoredPaths.intersect(loadedPaths)
                        )
                    }
                    pendingSelectAll = false
                    allMatchingSelected = false
                    finishSelectionLoad(showError = true)
                    return@runOnUiThread
                }

                pagingState.accumulated.clear()
                pagingState.accumulated.addAll(all)
                pagingState.totalCount = all.size
                pagingState.hasMore = false
                val availablePaths = all.mapTo(linkedSetOf()) { it.path }
                val selectedPaths = restoredPaths?.intersect(availablePaths)
                    ?: availablePaths
                pendingRestoredSelectionPaths = emptySet()
                pendingSelectAll = false

                renderRecordings(all) selectionCommit@{
                    if (!isAdded ||
                        view == null
                    ) {
                        return@selectionCommit
                    }
                    if (generation != pagingState.generation) return@selectionCommit
                    if (!recordingAdapter.selectMode) {
                        finishSelectionLoad(showError = false)
                        return@selectionCommit
                    }
                    recordingAdapter.restoreSelection(true, selectedPaths)
                    allMatchingSelected = restoredPaths == null
                    finishSelectionLoad(showError = false)
                }
            }
        }
    }

    private fun setSelectionActionsEnabled(enabled: Boolean) {
        // The bar's flat on-container colours carry no disabled state, so dim explicitly.
        listOfNotNull(btnSelectAll, btnDeleteSelected, btnShareSelected).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else DISABLED_ACTION_ALPHA
        }
        renderSelectionChrome()
    }

    private fun finishSelectionLoad(showError: Boolean) {
        selectionLoadInProgress = false
        setSelectionActionsEnabled(true)
        hideLoadStatus()
        if (showError) {
            appToast?.show(
                getString(R.string.recording_library_page_error), AppToast.Kind.ERROR)
        }
    }

    fun enterSelectionMode() {
        if (!::recordingAdapter.isInitialized || recordingAdapter.itemCount == 0) return
        recordingAdapter.enterSelectMode()
        onSelectionChanged(0)
    }

    fun setActiveRecording(path: String?) {
        if (::recordingAdapter.isInitialized) recordingAdapter.setActiveRecording(path)
    }

    fun shareRecording(recording: RecordingFile) {
        shareSingleRecording(recording)
    }

    /**
     * Full reload entry point for delete, filter apply, date change and
     * post-resume. Routes through [resetPaging] so the paged path is the only
     * one that ever loads a list.
     */
    private fun loadRecordingsForSelectedDate() {
        resetPaging()
        loadFirstPage()
    }

    /**
     * Drop accumulated rows + bump generation so any in-flight page result
     * is silently discarded. Cancels pending warmup polls. Called whenever
     * filters / date / segment change so the user starts paging from page 1
     * with the new criteria.
     */
    private fun resetPaging() {
        if (::recordingAdapter.isInitialized && recordingAdapter.selectMode) {
            if (allMatchingSelected) {
                pendingSelectAll = true
            } else if (pendingRestoredSelectionPaths.isEmpty()) {
                pendingRestoredSelectionPaths = recordingAdapter.getSelectedPaths()
            }
        }
        pagingState.generation += 1
        if (selectionLoadInProgress) {
            selectionLoadInProgress = false
            setSelectionActionsEnabled(true)
        }
        allMatchingSelected = false
        pagingState.page = 1
        pagingState.hasMore = true
        pagingState.loading = false
        pagingState.totalCount = 0
        pagingState.accumulated.clear()
        pagingState.fallbackActive = false
        retryTarget = null
        hideLoadStatus()
        warmupPollRunnable?.let { mainHandler.removeCallbacks(it) }
        warmupPollRunnable = null
    }

    /**
     * Translate the fragment's filter state ([currentFilter] / [extraFilter] /
     * [actorClassFilter] / [severityFilter] / [placeFilter] / date narrowing)
     * into the daemon's wire-level [RecordingsApiClient.Filter].
     *
     * Multi-type folding:
     *  - When the parent's Dashcam segment requests NORMAL + PROXIMITY +
     *    OEM_DASHCAM (extraFilter set), we send `types={normal, oemDashcam,
     *    proximity}` so all three appear together. The server-side
     *    `RecordingsApiHandler.buildFilter` honors the CSV list and uses
     *    `type IN (...)`.
     *  - Single-type queries (web events.html via `?type=normal`) still
     *    auto-fold oemDashcam under "normal" on the server for back-compat.
     *  - All other narrowing (severity / actor / place / date) maps directly.
     */
    private fun buildFilter(): RecordingsApiClient.Filter {
        // Build the type set first — extraFilter is the second-type signal
        // from the parent (Dashcam segment passes NORMAL + PROXIMITY).
        val typeSet = mutableSetOf<String>()
        fun addType(rf: RecordingFilter?) {
            when (rf) {
                RecordingFilter.NORMAL -> {
                    typeSet.add("normal")
                    typeSet.add("oemDashcam")
                }
                RecordingFilter.SENTRY -> typeSet.add("sentry")
                RecordingFilter.PROXIMITY -> typeSet.add("proximity")
                RecordingFilter.REPLAY -> typeSet.add("replay")
                RecordingFilter.ALL, null -> { /* leave empty = no narrowing */ }
            }
        }
        addType(currentFilter)
        addType(extraFilter)
        val typeStr: String?
        val typesParam: Set<String>
        if (typeSet.isEmpty()) {
            typeStr = null
            typesParam = emptySet()
        } else if (typeSet.size == 1) {
            // Single canonical type — use the legacy `type` field so the
            // server's auto-fold kicks in (matches web behavior).
            typeStr = typeSet.first()
            typesParam = emptySet()
        } else {
            // Multi-type: use the CSV path. Server emits `type=a,b,c`.
            typeStr = null
            typesParam = typeSet.toSet()
        }
        val dateStr = if (dateNarrowed) {
            String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                selectedDay
            )
        } else null
        val filterState = currentNarrowingState()
        return RecordingsApiClient.Filter(
            type = typeStr,
            types = typesParam,
            date = dateStr,
            classes = filterState.normalizedActorClasses,
            severities = filterState.normalizedSeverities,
            place = filterState.exactPlace,
            placeContains = filterState.normalizedPlaceContains.takeIf { it.isNotEmpty() },
            storages = filterState.normalizedStorages,
            parkingSessionId = filterState.normalizedParkingSessionId
        )
    }

    private fun currentNarrowingState() = RecordingLibraryFilterState(
        actorClasses = actorClassFilter,
        severities = severityFilter,
        places = placeFilter,
        placeContains = placeContainsQuery,
        storages = storageFilter,
        dateNarrowed = dateNarrowed,
        parkingSessionId = parkingSessionIdFilter
    )

    /**
     * Fetch page 1 with the current filter and render. Routes to fallback
     * scanner only if the daemon is unreachable; warming responses spin up
     * a poll loop that retries every 1.5s.
     */
    private fun loadFirstPage() {
        if (scanExecutor.isShutdown) return
        if (pagingState.loading) return

        val filter = buildFilter()
        pagingState.currentFilter = filter
        pagingState.page = 1
        pagingState.loading = true
        showFirstPageLoading()
        val gen = pagingState.generation
        val pageNum = 1
        val pageSize = pagingState.pageSize

        Log.d(TAG, "loadFirstPage filter=$filter")

        scanExecutor.submit {
            val page = try {
                RecordingsApiClient.fetchRecordings(filter, pageNum, pageSize)
            } catch (t: Throwable) {
                Log.w(TAG, "fetchRecordings page=1 threw: ${t.message}")
                null
            }

            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                if (gen != pagingState.generation) return@runOnUiThread  // stale
                pagingState.loading = false

                if (page == null) {
                    // Daemon unreachable → fallback to direct-FS scan.
                    loadFallback()
                    return@runOnUiThread
                }

                // Daemon is up but its recordings index is down. The clips are
                // on disk and the daemon is the only reader that can see
                // SD/USB volumes, so do NOT latch into the direct-FS fallback
                // (it would kill pagination and return nothing on external
                // storage). Keep whatever is on screen and poll with the same
                // cancel-and-backoff logic as the warmup path.
                if (page.indexUnavailable) {
                    // Render an explicit state. Without this the panel is
                    // completely blank on a fresh mount (recycler empty,
                    // emptyStateContainer starts GONE) while it silently
                    // re-polls — worse than the misleading "No recordings" it
                    // replaced.
                    showIndexDownState()
                    scheduleWarmupRetry(gen)
                    indexDownBackoffUsed = true
                    return@runOnUiThread
                }
                // The index answered, so any backoff accumulated by the
                // index-down poller above is stale. Reset it here (and NOT in
                // the warming branch's own reset below, which this path
                // early-returns past) so a subsequent warmup starts from the
                // 2s base instead of inheriting the 10s cap.
                if (indexDownBackoffUsed) {
                    indexDownBackoffUsed = false
                    warmupRetryAttempt = 0
                }

                if (page.warming) {
                    // Show building-index skeleton; schedule a retry.
                    showWarmingState(page.warmingDone, page.warmingTotal)
                    scheduleWarmupRetry(gen)
                    return@runOnUiThread
                }
                // Warmup is done — reset the exponential-backoff counter
                // so a future warmup window (storage hot-plug rebuild)
                // starts from the 2 s base again instead of continuing
                // at the 10 s cap from the prior cycle.
                warmupRetryAttempt = 0

                pagingState.totalCount = page.totalCount
                pagingState.accumulated.clear()
                pagingState.accumulated.addAll(page.recordings)
                // Server tells us totalPages directly — no need to guess.
                pagingState.hasMore = pageNum < page.totalPages
                renderRecordings(pagingState.accumulated.toList())
                hideLoadStatus()

                // Server says it's reconciling (storage hot-plug, type-
                // switch, etc.). Empty list now, but reconcile is in flight
                // and the populated rows land in ~1.5s. Schedule a one-shot
                // retry so the user doesn't have to manually pull-to-refresh.
                // Capped by the same generation guard above so a filter
                // change between fetch and retry properly cancels.
                if (page.reconciling && page.recordings.isEmpty()) {
                    mainHandler.postDelayed({
                        if (gen == pagingState.generation && isAdded && view != null) {
                            loadFirstPage()
                        }
                    }, page.retryAfterMs)
                }
            }
        }
    }

    /**
     * Fetch page N+1 and append. Guards against re-entry — the scroll
     * listener may fire multiple times before the previous request lands.
     */
    private fun loadNextPage() {
        if (scanExecutor.isShutdown) return
        if (pagingState.loading || !pagingState.hasMore || pagingState.fallbackActive) return

        pagingState.loading = true
        showLoadStatus(
            message = getString(R.string.recording_library_loading_more),
            showProgress = true,
            retry = null
        )
        val gen = pagingState.generation
        val pageNum = pagingState.page + 1
        val pageSize = pagingState.pageSize
        val filter = pagingState.currentFilter

        scanExecutor.submit {
            val page = try {
                RecordingsApiClient.fetchRecordings(filter, pageNum, pageSize)
            } catch (t: Throwable) {
                Log.w(TAG, "fetchRecordings page=$pageNum threw: ${t.message}")
                null
            }

            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                if (gen != pagingState.generation) return@runOnUiThread
                pagingState.loading = false

                if (page == null) {
                    // Keep loaded clips and leave paging retryable.
                    pagingState.hasMore = true
                    showLoadStatus(
                        message = getString(R.string.recording_library_page_error),
                        showProgress = false,
                        retry = RetryTarget.NEXT_PAGE
                    )
                    return@runOnUiThread
                }
                // Mid-paging warmup is rare but possible (index rebuild
                // triggered while user was browsing), and the index can also
                // go down mid-scroll. Either way: stop appending, keep the
                // rows already on screen rather than clearing them.
                if (page.warming || page.indexUnavailable) {
                    pagingState.hasMore = true
                    showLoadStatus(
                        message = getString(R.string.recording_library_page_unavailable),
                        showProgress = false,
                        retry = RetryTarget.NEXT_PAGE
                    )
                    return@runOnUiThread
                }

                pagingState.page = pageNum
                pagingState.totalCount = page.totalCount
                pagingState.accumulated.addAll(page.recordings)
                pagingState.hasMore = pageNum < page.totalPages
                renderRecordings(pagingState.accumulated.toList())
                hideLoadStatus()
            }
        }
    }

    /**
     * Schedule a warming-state retry. Cancels any prior pending retry so
     * we don't stack runnables on the main looper. Uses exponential backoff
     * (2 s → 4 s → 8 s → 10 s cap) so a long warmup (60+ s on a 2 k clip
     * library) doesn't hammer the daemon HTTP server with one request
     * per 1.5 s per connected client.
     */
    private fun scheduleWarmupRetry(gen: Int) {
        warmupPollRunnable?.let { mainHandler.removeCallbacks(it) }
        val attempt = warmupRetryAttempt.coerceAtMost(WARMING_POLL_MAX_ATTEMPTS_FOR_BACKOFF)
        val delay = (WARMING_POLL_INITIAL_MS shl attempt).coerceAtMost(WARMING_POLL_CAP_MS)
        warmupRetryAttempt++
        val r = Runnable {
            if (!isAdded || view == null) return@Runnable
            if (gen != pagingState.generation) return@Runnable
            warmupPollRunnable = null
            // Re-enter loadFirstPage; if still warming it'll re-schedule.
            loadFirstPage()
        }
        warmupPollRunnable = r
        mainHandler.postDelayed(r, delay)
    }

    /**
     * "Recordings index unavailable" state. Mirrors [showWarmingState]'s view
     * wiring so the panel is never left blank; the copy deliberately says the
     * clips are safe, because this is an index failure and NOT an empty
     * library. [scheduleWarmupRetry] polls us out of it on recovery.
     */
    private fun showIndexDownState() {
        if (currentList.isNotEmpty()) {
            showLoadStatus(
                message = getString(R.string.recording_lib_index_down_title),
                showProgress = false,
                retry = RetryTarget.FIRST_PAGE
            )
            return
        }
        currentList = emptyList()
        recordingAdapter.submitList(emptyList())
        recyclerRecordings.visibility = View.GONE
        initialLoadingContainer?.visibility = View.GONE
        emptyStateContainer?.visibility = View.VISIBLE
        tvEmptyState.visibility = View.VISIBLE
        tvEmptyState.text = getString(R.string.recording_lib_index_down_title)
        tvEmptyStateBody?.visibility = View.VISIBLE
        tvEmptyStateBody?.text = getString(R.string.recording_lib_index_down_body)
        // Filters aren't the reason the list is empty — hide the clear-filters
        // affordance so the user isn't sent down a dead end.
        btnEmptyClearFilters?.visibility = View.GONE
        btnEmptyRetry?.visibility = View.VISIBLE
        retryTarget = RetryTarget.FIRST_PAGE
        tvDayClipCount?.visibility = View.GONE
    }

    /**
     * Render the "Building library index" skeleton. Reuses the empty-state
     * container so we don't add a separate widget — message + progress
     * counter only. Hides the recycler.
     */
    private fun showWarmingState(done: Int, total: Int) {
        if (currentList.isNotEmpty()) {
            showLoadStatus(
                message = if (total > 0) {
                    getString(R.string.recording_lib_building_index_progress, done, total)
                } else {
                    getString(R.string.recording_lib_building_index_title)
                },
                showProgress = true,
                retry = null
            )
            return
        }
        currentList = emptyList()
        recordingAdapter.submitList(emptyList())
        recyclerRecordings.visibility = View.GONE
        initialLoadingContainer?.visibility = View.GONE
        emptyStateContainer?.visibility = View.VISIBLE
        tvEmptyState.visibility = View.VISIBLE
        tvEmptyState.text = getString(R.string.recording_lib_building_index_title)
        tvEmptyStateBody?.visibility = View.VISIBLE
        tvEmptyStateBody?.text = if (total > 0) {
            getString(R.string.recording_lib_building_index_progress, done, total)
        } else {
            getString(R.string.recording_lib_building_index_body)
        }
        btnEmptyClearFilters?.visibility = View.GONE
        btnEmptyRetry?.visibility = View.GONE
        // Reset day-clip pill — it would otherwise show the previous count.
        tvDayClipCount?.visibility = View.GONE
    }

    /**
     * Direct-FS fallback. Mirrors the legacy behavior: scan everything,
     * apply the same client-side filtering the API would have done, render
     * a single non-paged list. Disables the scroll listener so spurious
     * "near end" triggers don't try to fetch.
     */
    private fun loadFallback() {
        if (scanExecutor.isShutdown) return
        pagingState.fallbackActive = true
        pagingState.hasMore = false

        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val gen = pagingState.generation

        Log.d(TAG, "loadFallback (direct-FS) for $year-${month+1}-$selectedDay")

        scanExecutor.submit {
            try {
                val allRecordings = if (dateNarrowed) {
                    RecordingScanner.getRecordingsForDate(requireContext(), year, month, selectedDay)
                } else {
                    RecordingScanner.scanRecordings(requireContext())
                }

                val acceptedTypes = mutableSetOf<RecordingFile.RecordingType>()
                fun include(f: RecordingFilter) {
                    when (f) {
                        RecordingFilter.ALL -> {
                            acceptedTypes += RecordingFile.RecordingType.NORMAL
                            acceptedTypes += RecordingFile.RecordingType.SENTRY
                            acceptedTypes += RecordingFile.RecordingType.PROXIMITY
                            acceptedTypes += RecordingFile.RecordingType.OEM_DASHCAM
                            acceptedTypes += RecordingFile.RecordingType.REPLAY
                        }
                        RecordingFilter.NORMAL -> {
                            // NORMAL filter (parent: Dashcam segment) folds in OEM
                            // Dashcam clips so dvr_*.mp4 surfaces alongside cam_*.
                            acceptedTypes += RecordingFile.RecordingType.NORMAL
                            acceptedTypes += RecordingFile.RecordingType.OEM_DASHCAM
                        }
                        RecordingFilter.SENTRY ->     acceptedTypes += RecordingFile.RecordingType.SENTRY
                        RecordingFilter.PROXIMITY ->  acceptedTypes += RecordingFile.RecordingType.PROXIMITY
                        RecordingFilter.REPLAY ->     acceptedTypes += RecordingFile.RecordingType.REPLAY
                    }
                }
                include(currentFilter)
                extraFilter?.let { include(it) }
                val typeFiltered = allRecordings.filter { it.type in acceptedTypes }

                val filterState = currentNarrowingState()
                val recordings = typeFiltered.filter { rec ->
                    filterState.matchesFallback(
                        storageType = rec.storageType,
                        placeShortLabel = rec.placeShortLabel,
                        placeMediumLabel = rec.placeMediumLabel,
                        placeDisplayName = rec.placeDisplayName,
                        actorClasses = rec.actorClasses,
                        peakSeverity = rec.peakSeverity,
                        hasSidecar = rec.peakSeverity != null || rec.actorClasses.isNotEmpty()
                    )
                }

                activity?.runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    if (gen != pagingState.generation) return@runOnUiThread
                    pagingState.accumulated.clear()
                    pagingState.accumulated.addAll(recordings)
                    pagingState.totalCount = recordings.size
                    renderRecordings(recordings)
                    hideLoadStatus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fallback scan error", e)
                activity?.runOnUiThread {
                    if (!isAdded || view == null || gen != pagingState.generation) {
                        return@runOnUiThread
                    }
                    showFatalLoadState()
                }
            }
        }
    }

    private fun renderRecordings(
        recordings: List<RecordingFile>,
        onCommitted: (() -> Unit)? = null
    ) {
        currentList = recordings

        // Day clip count pill on the date jump card. Only meaningful while
        // a single day is selected — across-all-days the parent header
        // already shows the global total, and a second pill saying e.g.
        // "320 clips" next to "All days" is just noise.
        tvDayClipCount?.let { pill ->
            if (dateNarrowed && recordings.isNotEmpty()) {
                pill.visibility = View.VISIBLE
                pill.text = resources.getQuantityStringSafe(
                    R.string.recording_lib_clip_count_one,
                    R.string.recording_lib_clip_count,
                    recordings.size
                )
            } else {
                pill.visibility = View.GONE
            }
        }

        onListChanged?.invoke(recordings)

        // Decoration mode: time-of-day buckets only when the user has narrowed
        // to a single day. When the list spans every day, group by date instead
        // — otherwise a clip from Tuesday morning and one from Friday morning
        // both fall under "MORNING" together, which reads as a bug.
        sectionHeaderDecoration?.singleDayMode = dateNarrowed

        if (recordings.isEmpty()) {
            recyclerRecordings.visibility = View.GONE
            initialLoadingContainer?.visibility = View.GONE
            emptyStateContainer?.visibility = View.VISIBLE
            tvEmptyState.visibility = View.VISIBLE

            // Distinguish "filters too narrow" from "nothing on disk" so the
            // empty-state body and CTA make sense.
            //
            // hasFilters = anything that could be narrowing the visible set.
            // The segment selector itself (currentFilter) doesn't count —
            // dropping that just sends the user to the other tab and the
            // disk could still be empty there too.
            val hasFilters = currentNarrowingState().hasActiveNarrowing
            btnEmptyRetry?.visibility = View.GONE
            if (hasFilters) {
                tvEmptyState.text = getString(R.string.recording_lib_empty_title)
                tvEmptyStateBody?.visibility = View.VISIBLE
                tvEmptyStateBody?.text = getString(R.string.recording_lib_empty_body)
                btnEmptyClearFilters?.visibility = View.VISIBLE
            } else {
                tvEmptyState.text = getString(R.string.recording_lib_empty_disk_title)
                tvEmptyStateBody?.visibility = View.VISIBLE
                tvEmptyStateBody?.text = getString(R.string.recording_lib_empty_disk_body)
                btnEmptyClearFilters?.visibility = View.GONE
            }
            recordingAdapter.submitList(emptyList()) {
                onRenderedListCommitted()
                onCommitted?.invoke()
            }
        } else {
            recyclerRecordings.visibility = View.VISIBLE
            initialLoadingContainer?.visibility = View.GONE
            emptyStateContainer?.visibility = View.GONE
            tvEmptyState.visibility = View.GONE
            recordingAdapter.submitList(recordings) {
                // After the diff applies, the decoration reads `currentList`
                // and re-paints sections.
                recyclerRecordings.invalidateItemDecorations()
                onRenderedListCommitted()
                onCommitted?.invoke()
            }
        }
    }

    private fun onRenderedListCommitted() {
        if (!::recordingAdapter.isInitialized) return
        if (pendingSelectAll && recordingAdapter.selectMode) {
            selectAllRecordings()
        } else if (pendingRestoredSelectionPaths.isNotEmpty()) {
            resolveRestoredSelectionIfNeeded()
        } else if (!selectionLoadInProgress && recordingAdapter.selectMode) {
            recordingAdapter.retainSelection(
                currentList.mapTo(mutableSetOf()) { it.path }
            )
        }
        // After layout, so the visible window is known.
        if (safeKeepActive) recyclerRecordings.post { refreshSafeKeepStates() }
    }

    private fun showFirstPageLoading() {
        retryTarget = null
        btnEmptyRetry?.visibility = View.GONE
        if (currentList.isEmpty()) {
            recyclerRecordings.visibility = View.GONE
            emptyStateContainer?.visibility = View.GONE
            initialLoadingContainer?.visibility = View.VISIBLE
            hideLoadStatus()
        } else {
            showLoadStatus(
                message = getString(R.string.recording_library_refreshing),
                showProgress = true,
                retry = null
            )
        }
    }

    private fun showFatalLoadState() {
        retryTarget = RetryTarget.FIRST_PAGE
        if (currentList.isNotEmpty()) {
            showLoadStatus(
                message = getString(R.string.recording_library_refresh_error),
                showProgress = false,
                retry = RetryTarget.FIRST_PAGE
            )
            return
        }
        initialLoadingContainer?.visibility = View.GONE
        recyclerRecordings.visibility = View.GONE
        emptyStateContainer?.visibility = View.VISIBLE
        tvEmptyState.text = getString(R.string.recording_library_fatal_title)
        tvEmptyStateBody?.text = getString(R.string.recording_library_fatal_body)
        tvEmptyStateBody?.visibility = View.VISIBLE
        btnEmptyClearFilters?.visibility = View.GONE
        btnEmptyRetry?.visibility = View.VISIBLE
    }

    private fun showLoadStatus(
        message: String,
        showProgress: Boolean,
        retry: RetryTarget?
    ) {
        retryTarget = retry
        loadStatusBar?.visibility = View.VISIBLE
        loadStatusProgress?.visibility = if (showProgress) View.VISIBLE else View.GONE
        tvLoadStatus?.text = message
        btnLoadRetry?.visibility = if (retry != null) View.VISIBLE else View.GONE
    }

    private fun hideLoadStatus() {
        retryTarget = null
        loadStatusBar?.visibility = View.GONE
        btnLoadRetry?.visibility = View.GONE
    }

    private fun retryFailedLoad() {
        when (retryTarget) {
            RetryTarget.NEXT_PAGE -> loadNextPage()
            RetryTarget.FIRST_PAGE, null -> loadFirstPage()
        }
    }

    // Tiny helper: pluralized "%d clip(s)" without forcing a strings.xml plural
    // (we already keep both forms separately for fast reuse).
    private fun android.content.res.Resources.getQuantityStringSafe(
        oneRes: Int, otherRes: Int, count: Int
    ): String {
        return if (count == 1) getString(oneRes, count) else getString(otherRes, count)
    }

    // -----------------------------------------------------------------
    // Playback / delete
    // -----------------------------------------------------------------

    private fun playRecording(recording: RecordingFile) {
        onPlayRecording?.let {
            it(recording)
            return
        }
        try {
            // Build a playlist from the currently visible list so the player
            // can offer prev/next that respects the user's filters & date.
            // The visible list is newest-first (ts_ms DESC); the player treats
            // index+1 as "next", so feed it in chronological order (oldest->
            // newest) and "next" / auto-advance lands on the NEWER clip while
            // the on-screen library stays descending.
            val ordered = currentList.asReversed()
            val paths = ordered.map { it.playbackSource }.toTypedArray()
            val titles = ordered.map { it.name }.toTypedArray()
            val idx = ordered.indexOfFirst { it.path == recording.path }
            val bundle = Bundle().apply {
                putString(VideoPlayerFragment.ARG_VIDEO_PATH, recording.playbackSource)
                putString(VideoPlayerFragment.ARG_VIDEO_TITLE, recording.name)
                if (paths.isNotEmpty()) {
                    putStringArray(VideoPlayerFragment.ARG_PLAYLIST_PATHS, paths)
                    putStringArray(VideoPlayerFragment.ARG_PLAYLIST_TITLES, titles)
                    putInt(VideoPlayerFragment.ARG_PLAYLIST_INDEX, idx.coerceAtLeast(0))
                }
            }
            androidx.navigation.fragment.NavHostFragment.findNavController(this)
                .navigate(
                    R.id.action_global_videoPlayer,
                    bundle,
                    com.overdrive.app.ui.util.NavOptionsExt.m3SharedAxisZ()
                )
        } catch (e: Exception) {
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
                appToast?.show(
                    getString(R.string.toast_cannot_play_video, e2.message ?: ""),
                    AppToast.Kind.ERROR)
            }
        }
    }

    /**
     * Per-tile share — invokes the system chooser with a single video URI
     * vended through FileProvider so external apps (Telegram, Mail,
     * messenger, etc.) can read the clip without a runtime grant.
     */
    private fun shareSingleRecording(recording: RecordingFile) {
        val ctx = context ?: return
        try {
            val uri = recording.contentUri ?: FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                recording.file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
        } catch (e: Exception) {
            Log.e(TAG, "Share failed", e)
            appToast?.show(e.message ?: getString(R.string.toast_share_failed), AppToast.Kind.ERROR)
        }
    }

    private fun confirmCreateIncidentPack(recording: RecordingFile) {
        if (recording.recordingId.isNullOrEmpty()) {
            appToast?.show(
                getString(R.string.toast_evidence_pack_unavailable), AppToast.Kind.WARNING)
            return
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_smart_toy)
            .setTitle(getString(R.string.dialog_create_evidence_pack_title))
            .setMessage(getString(R.string.dialog_create_evidence_pack_message))
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.action_create_evidence_pack)) { _, _ ->
                createIncidentPack(recording.recordingId)
            }
            .show()
    }

    private fun createIncidentPack(recordingId: String) {
        if (scanExecutor.isShutdown ||
            !incidentPackInProgress.compareAndSet(false, true)
        ) {
            return
        }
        val cache = File(requireContext().cacheDir, "incident-packs")
        appToast?.show(
            getString(R.string.toast_evidence_pack_creating), AppToast.Kind.INFO)
        scanExecutor.submit {
            val result = try {
                RecordingsApiClient.createIncidentPack(recordingId, cache)
            } finally {
                incidentPackInProgress.set(false)
            }
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                val file = result.file
                if (file == null) {
                    appToast?.show(
                        getString(
                            R.string.toast_evidence_pack_failed,
                            result.error ?: getString(R.string.error_unknown)
                        ),
                        AppToast.Kind.ERROR
                    )
                    return@runOnUiThread
                }
                shareIncidentPack(file)
            }
        }
    }

    private fun shareIncidentPack(file: File) {
        val ctx = context ?: return
        try {
            val uri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appToast?.show(
                getString(R.string.toast_evidence_pack_ready), AppToast.Kind.SUCCESS)
            startActivity(
                Intent.createChooser(
                    intent,
                    getString(R.string.action_share_evidence_pack)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Evidence-pack share failed", e)
            appToast?.show(
                getString(
                    R.string.toast_evidence_pack_failed,
                    e.message ?: getString(R.string.error_unknown)
                ),
                AppToast.Kind.ERROR
            )
        }
    }

    /**
     * Multi-select share — bulk dispatches the selected clips through
     * ACTION_SEND_MULTIPLE so any chooser target can ingest the whole
     * batch in one tap. Falls back gracefully if no app handles it.
     */
    private fun shareSelectedRecordings() {
        val ctx = context ?: return
        val selected = recordingAdapter.getSelectedRecordings()
        if (selected.isEmpty()) return
        try {
            val uris = ArrayList<android.net.Uri>(selected.size)
            for (rec in selected) {
                val u = rec.contentUri ?: FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.fileprovider",
                    rec.file
                )
                uris.add(u)
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/mp4"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
            appToast?.show(
                getString(R.string.toast_share_recordings, selected.size), AppToast.Kind.INFO)
            exitSelectMode()
        } catch (e: Exception) {
            Log.e(TAG, "Bulk share failed", e)
            appToast?.show(e.message ?: getString(R.string.toast_share_failed), AppToast.Kind.ERROR)
        }
    }

    private fun confirmDelete(recording: RecordingFile) {
        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_delete)
            .setTitle(getString(R.string.dialog_delete_recording_title))
            .setMessage(getString(R.string.dialog_delete_recording_message, recording.name))
            .setNegativeButton(getString(R.string.action_cancel), null)
            .setPositiveButton(getString(R.string.dialog_delete)) { _, _ ->
                deleteRecording(recording)
            }
            .show()
    }

    private fun deleteRecording(recording: RecordingFile) {
        if (scanExecutor.isShutdown) return
        scanExecutor.submit {
            val deleted = RecordingScanner.deleteRecording(recording)
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                if (deleted) {
                    appToast?.show(
                        getString(R.string.toast_recording_deleted), AppToast.Kind.SUCCESS)
                    loadRecordingsForSelectedDate()
                    onContentChanged?.invoke()
                } else {
                    appToast?.show(
                        getString(R.string.toast_recording_delete_failed), AppToast.Kind.ERROR)
                }
            }
        }
    }

    private fun confirmBatchDelete() {
        val selected = recordingAdapter.getSelectedRecordings()
        if (selected.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Overdrive_M3_Dialog)
            .setIcon(R.drawable.ic_delete)
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
                    if (failed > 0) {
                        appToast?.show(
                            getString(R.string.toast_batch_delete_partial, deleted, failed),
                            AppToast.Kind.WARNING)
                    } else {
                        appToast?.show(
                            resources.getQuantityString(
                                R.plurals.recordings_deleted_count, deleted, deleted),
                            AppToast.Kind.SUCCESS)
                    }
                    exitSelectMode()
                    loadRecordingsForSelectedDate()
                    // Same notification as single-delete — keeps the
                    // parent's chip set in sync after a multi-clip purge.
                    onContentChanged?.invoke()
                }
            }
        }
    }

    private fun exitSelectMode() {
        pendingRestoredSelectionPaths = emptySet()
        pendingSelectAll = false
        allMatchingSelected = false
        selectionLoadInProgress = false
        setSelectionActionsEnabled(true)
        hideLoadStatus()
        recordingAdapter.exitSelectMode()
        renderSelectionChrome()
    }

    override fun onResume() {
        super.onResume()
        // When embedded, the parent's onResume re-applies every filter
        // dimension atomically via applyAll(). Re-loading here using THIS
        // fragment's own (possibly stale) date state caused a brief flash
        // of the wrong list before the parent's apply landed.
        val embedded = arguments?.getBoolean(ARG_HIDE_INTERNAL_FILTERS, false) == true
        if (!embedded) loadRecordingsForSelectedDate()
        safeKeepResumed = true
        safeKeepRechecksLeft = SAFEKEEP_RECHECKS
        checkSafeKeep()
    }

    override fun onPause() {
        safeKeepResumed = false
        cancelSafeKeepPoll()
        mainHandler.removeCallbacks(safeKeepRecheck)
        cancelPendingMenu()
        prefetchRead?.cancel(false)
        prefetchRead = null
        super.onPause()
    }

    // -----------------------------------------------------------------
    // SafeKeep pills + actions
    // -----------------------------------------------------------------

    private fun checkSafeKeep() {
        val executor = safeKeepExecutor?.takeUnless { it.isShutdown } ?: return
        val gen = safeKeepGeneration
        executor.execute {
            val active = SafeKeepClipClient.isActive()
            mainHandler.post {
                if (gen != safeKeepGeneration || view == null || !::recordingAdapter.isInitialized) {
                    return@post
                }
                if (active == null) {
                    // No answer (daemon busy or restarting) is not "off": keep what is shown and ask again.
                    if (safeKeepResumed) {
                        val delay = if (safeKeepRechecksLeft-- > 0) SAFEKEEP_RECHECK_MS else SAFEKEEP_RECHECK_SLOW_MS
                        mainHandler.removeCallbacks(safeKeepRecheck)
                        mainHandler.postDelayed(safeKeepRecheck, delay)
                    }
                    if (safeKeepActive) refreshSafeKeepStates()
                    return@post
                }
                safeKeepActive = active
                if (active) {
                    // Menus offer SafeKeep from now on, even before the first clip states arrive.
                    recordingAdapter.setSafeKeepStates(true, HashMap(safeKeepStates))
                    refreshSafeKeepStates()
                } else {
                    clearSafeKeep()
                }
            }
        }
    }

    private fun clearSafeKeep() {
        cancelSafeKeepPoll()
        safeKeepStates.clear()
        safeKeepReadAt.clear()
        safeKeepUploadGraceUntil.clear()
        if (::recordingAdapter.isInitialized) recordingAdapter.setSafeKeepStates(false, emptyMap())
    }

    /** Coalesced: at most one request in flight, one queued behind it. */
    private fun refreshSafeKeepStates() {
        cancelSafeKeepPoll()
        if (!safeKeepActive || !safeKeepResumed || view == null || !::recordingAdapter.isInitialized) return
        if (safeKeepFetchInFlight) {
            safeKeepFetchQueued = true
            return
        }
        val names = safeKeepWindowNames()
        if (names.isEmpty()) return
        val executor = safeKeepExecutor?.takeUnless { it.isShutdown } ?: return
        val gen = safeKeepGeneration
        safeKeepFetchInFlight = true
        executor.execute {
            val result = SafeKeepClipClient.clipStates(names)
            mainHandler.post {
                if (gen != safeKeepGeneration) return@post
                onSafeKeepStates(names, result)
            }
        }
    }

    private fun onSafeKeepStates(asked: List<String>, result: SafeKeepClipClient.StatesResult?) {
        safeKeepFetchInFlight = false
        if (view == null || !::recordingAdapter.isInitialized) return
        if (result?.disabled == true) {
            safeKeepActive = false
            clearSafeKeep()
            return
        }
        if (result != null && safeKeepActive) {
            mergeClipStates(asked, result.states)
            recordingAdapter.setSafeKeepStates(true, HashMap(safeKeepStates))
        }
        if (safeKeepFetchQueued) {
            safeKeepFetchQueued = false
            refreshSafeKeepStates()
            return
        }
        val busy = asked.any { safeKeepStates[it]?.inFlight == true }
        if (safeKeepActive && safeKeepResumed && (busy || result == null)) {
            // No answer is retried more slowly than an upload in progress is polled.
            mainHandler.postDelayed(safeKeepPoll, if (result == null) SAFEKEEP_RECHECK_MS else SAFEKEEP_POLL_MS)
        }
    }

    /** File names around the visible rows, capped at the endpoint's per-call limit. */
    private fun safeKeepWindowNames(): List<String> {
        val list = recordingAdapter.currentList
        if (list.isEmpty()) return emptyList()
        val lm = recyclerRecordings.layoutManager as? GridLayoutManager
        var first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        var last = lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            first = 0
            last = 0
        }
        val from = (first - SAFEKEEP_WINDOW_PAD).coerceAtLeast(0)
        val to = (last + SAFEKEEP_WINDOW_PAD).coerceAtMost(list.size - 1)
        val out = LinkedHashSet<String>()
        for (i in from..to) {
            out.add(list[i].file.name)
            if (out.size >= SafeKeepClipClient.MAX_FILES_PER_CALL) break
        }
        return out.toList()
    }

    private fun onSafeKeepAction(recording: RecordingFile, action: RecordingAdapter.SafeKeepAction) {
        val name = recording.file.name
        if (action == RecordingAdapter.SafeKeepAction.OPEN_CLOUD) {
            val link = recordingAdapter.safeKeepState(name)?.link
            if (link == null) {
                appToast?.show(getString(R.string.toast_safekeep_open_failed), AppToast.Kind.ERROR)
                return
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            } catch (e: Exception) {
                appToast?.show(getString(R.string.toast_safekeep_open_failed), AppToast.Kind.ERROR)
            }
            return
        }
        val executor = safeKeepExecutor?.takeUnless { it.isShutdown } ?: return
        val gen = safeKeepGeneration
        // Stale from the tap on: a ⋮ reopened while this runs, or after it, re-reads the clip.
        safeKeepReadAt.remove(name)
        // Counted: a second tap on the same clip stays protected until its own call returns.
        val firstPending = !safeKeepActionPending.containsKey(name)
        safeKeepActionPending[name] = (safeKeepActionPending[name] ?: 0) + 1
        // Optimistic: the menu and pill show the outcome from the tap; a failure reverts it below.
        val prior = safeKeepStates[name]
        val optimistic = when (action) {
            RecordingAdapter.SafeKeepAction.UPLOAD_NOW ->
                (prior ?: SafeKeepClipClient.ClipState("queued", 0, null, null, false))
                    .let { if (it.inFlight || it.state == "saved") it else it.copy(state = "queued") }
            RecordingAdapter.SafeKeepAction.LOCK ->
                prior?.copy(locked = true) ?: SafeKeepClipClient.ClipState("none", 0, null, null, true)
            else -> prior?.copy(locked = false)
        }
        if (optimistic != null && safeKeepActive) {
            safeKeepStates[name] = optimistic
            recordingAdapter.setSafeKeepStates(true, HashMap(safeKeepStates))
        }
        executor.execute {
            val result = when (action) {
                RecordingAdapter.SafeKeepAction.UPLOAD_NOW -> SafeKeepClipClient.uploadNow(name)
                RecordingAdapter.SafeKeepAction.LOCK -> SafeKeepClipClient.setLocked(name, true)
                else -> SafeKeepClipClient.setLocked(name, false)
            }
            mainHandler.post {
                if (gen != safeKeepGeneration || view == null) return@post
                val left = (safeKeepActionPending[name] ?: 1) - 1
                if (left <= 0) safeKeepActionPending.remove(name) else safeKeepActionPending[name] = left
                safeKeepReadAt.remove(name)
                if (result.error == "disabled") {
                    // The daemon's 409 "disabled": SafeKeep was switched off elsewhere; drop its pills and items.
                    safeKeepActive = false
                    clearSafeKeep()
                }
                // Revert only a lone action; with several in flight [prior] may be another optimistic
                // state, so the follow-up read restores the truth instead.
                if (!result.ok && firstPending && left <= 0 && optimistic != null &&
                    safeKeepStates[name] == optimistic
                ) {
                    if (prior == null) safeKeepStates.remove(name) else safeKeepStates[name] = prior
                    if (safeKeepActive) recordingAdapter.setSafeKeepStates(true, HashMap(safeKeepStates))
                }
                if (result.ok && action == RecordingAdapter.SafeKeepAction.UPLOAD_NOW) {
                    // The daemon queues it on its own thread; give a read that still says idle a moment.
                    safeKeepUploadGraceUntil[name] = SystemClock.uptimeMillis() + SAFEKEEP_UPLOAD_GRACE_MS
                }
                if (result.ok) {
                    val msg = when (action) {
                        RecordingAdapter.SafeKeepAction.UPLOAD_NOW -> R.string.toast_safekeep_upload_queued
                        RecordingAdapter.SafeKeepAction.LOCK -> R.string.toast_safekeep_locked
                        else -> R.string.toast_safekeep_unlocked
                    }
                    appToast?.show(getString(msg), AppToast.Kind.SUCCESS)
                } else {
                    val msg = when (result.error) {
                        "no_destination" -> getString(R.string.toast_safekeep_no_storage)
                        "lock_quota", "lock_full" -> getString(R.string.toast_safekeep_lock_full)
                        else -> getString(
                            R.string.toast_safekeep_action_failed,
                            result.message ?: result.error ?: getString(R.string.error_unknown)
                        )
                    }
                    appToast?.show(msg, AppToast.Kind.ERROR)
                }
                cancelSafeKeepPoll()
                mainHandler.postDelayed(safeKeepPoll, SAFEKEEP_ACTION_REFRESH_MS)
            }
        }
    }

    override fun onDestroyView() {
        // Lifecycle-safe: dismiss any open sheet & clear decoration ref so
        // the GC can collect the fragment cleanly.
        filterSheet?.setOnDismissListener(null)
        filterSheet?.dismiss()
        filterSheet = null
        sectionHeaderDecoration?.let { recyclerRecordings.removeItemDecoration(it) }
        sectionHeaderDecoration = null
        if (pagingScrollListenerAttached) {
            recyclerRecordings.removeOnScrollListener(pagingScrollListener)
            pagingScrollListenerAttached = false
        }
        warmupPollRunnable?.let { mainHandler.removeCallbacks(it) }
        warmupPollRunnable = null
        cancelSafeKeepPoll()
        recyclerRecordings.removeOnScrollListener(safeKeepScrollListener)
        safeKeepGeneration += 1
        // shutdown, not shutdownNow: a tapped Lock/Upload still queued must reach the daemon.
        safeKeepExecutor?.shutdown()
        safeKeepExecutor = null
        safeKeepActive = false
        safeKeepFetchInFlight = false
        safeKeepFetchQueued = false
        safeKeepStates.clear()
        safeKeepReadAt.clear()
        safeKeepActionPending.clear()
        safeKeepUploadGraceUntil.clear()
        cancelPendingMenu()
        prefetchRead?.cancel(false)
        prefetchRead = null
        mainHandler.removeCallbacks(safeKeepRecheck)
        appToast?.cancel()
        appToast = null
        skeleton?.cancel()
        skeleton = null
        pendingRestoredSelectionPaths = emptySet()
        pendingSelectAll = false
        allMatchingSelected = false
        selectionLoadInProgress = false
        // Bump generation so any in-flight background result lands as stale.
        pagingState.generation += 1
        // shutdownNow so a stuck disk walk doesn't hold the fragment alive.
        scanExecutor.shutdownNow()
        if (::recordingAdapter.isInitialized) {
            recyclerRecordings.adapter = null
            recordingAdapter.dispose()
        }
        currentList = emptyList()
        super.onDestroyView()
    }
}
