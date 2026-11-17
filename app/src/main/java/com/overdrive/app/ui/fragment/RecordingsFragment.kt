package com.overdrive.app.ui.fragment

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.format.Formatter
import android.text.style.ReplacementSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.sidesheet.SideSheetDialog
import com.overdrive.app.R
import com.overdrive.app.ui.model.RecordingFile
import com.overdrive.app.ui.util.RecordingScanner
import com.overdrive.app.ui.util.RecordingUiText
import com.overdrive.app.ui.util.RecordingsApiClient
import com.overdrive.app.ui.util.navigateDrillDown
import com.overdrive.app.ui.widget.Skeleton
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.overdrive.app.ui.timeline.TimelineFragment
import com.overdrive.app.ui.timeline.TimelineLinks
import com.overdrive.app.ui.timeline.TimelinePrefs

/**
 * Recordings page — single native two-pane surface.
 *
 * The top header bundles every filter the user can reach (events.html parity):
 *   • Title + Settings.
 *   • Counts subtitle ("X today · Y total · Z GB").
 *   • Dashcam | Surveillance segmented control with running per-segment counts.
 *   • Date jump card (◀ Today / clip count ▶) opening MaterialDatePicker on tap.
 *   • What chip row (Any / Person / Vehicle / Bike / Animal) — additive.
 *   • Severity chip row (Any / Alert / Critical) — additive.
 *   • Reset chip-button (visible only while filters narrow the list).
 *
 * The embedded [RecordingLibraryFragment] hosts the recyclerview only — its
 * own date row + filter bar are hidden via [RecordingLibraryFragment.newInstanceEmbedded].
 *
 * In landscape, taps swap the right preview pane for an inline
 * [VideoPlayerFragment]; the visible recording list (post-filter) is passed
 * through as a playlist so the player can offer prev/next buttons.
 */
class RecordingsFragment : Fragment() {

    private var libraryFragment: RecordingLibraryFragment? = null
    private var currentSource: Source = Source.ALL

    // -------- Filter state owned at this level --------
    private val actorClassFilter = mutableSetOf<String>()  // lowercase
    private val severityFilter = mutableSetOf<String>()    // upper
    // Physical-volume filter: "INTERNAL" / "SD_CARD" / "USB". Empty = all
    // volumes (default). Applies to BOTH Dashcam and Surveillance segments —
    // the daemon index already spans every storage location, so this just
    // narrows the view to where each clip physically landed.
    private val storageFilter = mutableSetOf<String>()
    /**
     * Dashcam-only: narrow visible list to a specific type. "NORMAL" =
     * continuous-record cam_* clips, "PROXIMITY" = radar-triggered clips.
     * Empty set => show both (the default). Both selected => same as empty.
     */
    private val dashcamTypes = mutableSetOf<String>()
    private var filterSheet: AppCompatDialog? = null

    /**
     * v3 place filter. Selected short-labels (district / city, lowercased
     * for case-insensitive match against [RecordingFile.placeShortLabel]).
     * Empty set => no place narrowing.
     *
     * The chip ROW is dynamically populated from the places actually
     * present in the current scan — there's no fixed taxonomy. We can't
     * know in advance whether a user lives in "Cheras" or "Munich," so
     * the chips are derived per-page and re-populated whenever the
     * library reports a new list via [RecordingLibraryFragment.onListChanged].
     */
    private val placeFilter = mutableSetOf<String>()

    /**
     * Snapshot of every distinct place short-label that has appeared in a
     * recording in the current scan, in descending count order. Capped at
     * MAX_PLACE_CHIPS so the chip row doesn't sprawl on a long road trip.
     * Empty until the first scan completes.
     */
    private var availablePlaces: List<String> = emptyList()

    /**
     * Free-text place search query (landscape header EditText). Stacks with
     * the chip [placeFilter] — server applies both. Empty string disables
     * the placeContains narrowing dimension. Updated via a 300ms debounce
     * driven by [placeSearchRunnable] so per-keystroke the API isn't hit.
     */
    private var placeContainsQuery: String = ""

    /**
     * Parking Intelligence: narrow the list to the clips stamped with one
     * parking session. Set only when the user arrives from a park's "Open
     * events" (WebViewFragment forwards the `parkingSessionId` deep-link
     * param); shown as a dismissible chip, cleared by its close icon or
     * Reset. Empty = no narrowing.
     */
    private var parkingSessionId: String = ""

    /**
     * Pending debounce post for the place-search EditText. Cancelled in
     * onDestroyView and on each new keystroke so only the final keystroke
     * triggers an API roundtrip.
     */
    private var placeSearchRunnable: Runnable? = null

    /**
     * Pending warming-state poll. The daemon's H2 index can be rebuilding
     * on cold start; we re-poll /api/recordings/stats with exponential
     * backoff while warming=true so the header counters land as soon as
     * the index is ready without hammering the HTTP server during a long
     * warmup window. Cancelled in onDestroyView.
     */
    private var warmingRetryRunnable: Runnable? = null
    /**
     * Exponential-backoff counter for the warming-state stats poll. Resets
     * to zero whenever a non-warming response lands.
     */
    private var warmingRetryAttempt: Int = 0

    // -------- Date state --------
    private val calendar = Calendar.getInstance()
    private var selectedDay = calendar.get(Calendar.DAY_OF_MONTH)
    /**
     * Whether the visible list is narrowed to a single day. False until the
     * user explicitly picks a date or taps prev/next-day. The default
     * "today" date stored in [calendar] / [selectedDay] is just the picker
     * pre-selection; without this flag the page would silently filter every
     * recording that wasn't captured today.
     */
    private var dateNarrowed: Boolean = false
    private val dayHeaderFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    // -------- Playlist for inline player prev/next --------
    private var currentPlaylist: List<RecordingFile> = emptyList()
    private var currentInlineIndex: Int = -1
    private var activeInlinePath: String? = null
    private var selectedInlineRecording: RecordingFile? = null
        set(value) {
            val changed = value?.path != field?.path
            field = value
            // Its ⋮ needs SafeKeep state even when the clip is far outside the list's fetched window.
            if (changed && value != null) libraryFragment?.prefetchSafeKeepState(value)
        }

    /**
     * Whether the inline player is currently maximized (chrome hidden +
     * preview pane occupying the whole screen). Only ever true on landscape
     * with an active inline player; portrait has no inline pane.
     */
    private var playerFullscreen: Boolean = false

    /**
     * Back-press hook installed only while the player is maximized. Pressing
     * back collapses fullscreen first; subsequent back presses fall through
     * to normal nav. Disabled (and removed from the dispatcher's relevant
     * set) when not in fullscreen so it never gets in the way.
     */
    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setPlayerFullscreen(false)
        }
    }

    /** Single-thread executor for filesystem scans. Recreated per view. */
    private var metricsExecutor: ExecutorService? = null
    private var skeleton: Skeleton? = null

    /** Main-thread handler for posting scan results back to the UI. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Pending UI posts so onDestroyView can cancel any in-flight scan
     * results before the view is gone. Touched from both the metrics
     * executor (add) and the main thread (remove + iteration), so all
     * mutations are guarded by `synchronized(pendingPosts)`.
     */
    private val pendingPosts = mutableListOf<Runnable>()

    // -------- List | Timeline --------
    private var timelineMode = false
    private var timelineModeResolved = false
    private var timelineStartMs = -1L
    private var timelineEndMs = -1L
    private var timelineDayMs = -1L
    private var suppressViewToggle = false
    /** List chrome the timeline hid, with the visibility each had. */
    private val timelineHiddenViews = mutableMapOf<View, Int>()

    enum class Source { ALL, DASHCAM, REPLAYS, SURVEILLANCE }

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_recordings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore segment + filter state across rotation.
        savedInstanceState?.let { state ->
            currentSource = state.getString(KEY_SOURCE)
                ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
                ?: Source.ALL
            state.getStringArray(KEY_ACTORS)?.let { actorClassFilter.addAll(it) }
            state.getStringArray(KEY_SEVERITY)?.let { severityFilter.addAll(it) }
            state.getStringArray(KEY_DASHCAM_TYPES)?.let { dashcamTypes.addAll(it) }
            state.getStringArray(KEY_PLACES)
                ?.firstOrNull()
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?.let(placeFilter::add)
            state.getStringArray(KEY_STORAGE)?.let { storageFilter.addAll(it) }
            val y = state.getInt(KEY_DATE_Y, -1)
            val m = state.getInt(KEY_DATE_M, -1)
            val d = state.getInt(KEY_DATE_D, -1)
            if (y > 0 && m >= 0 && d > 0) {
                calendar.set(y, m, 1)
                selectedDay = d
            }
            dateNarrowed = state.getBoolean(KEY_DATE_NARROWED, false)
            playerFullscreen = state.getBoolean(KEY_PLAYER_FULLSCREEN, false)
            placeContainsQuery = state.getString(KEY_PLACE_CONTAINS, "") ?: ""
            parkingSessionId = state.getString(KEY_PARKING_SESSION, "") ?: ""
        }

        // Deep-link fallback: WebViewFragment routes an events-link it can't
        // resolve to a specific clip here, passing the clip's `filter` type so
        // we at least land on the right segment. Only honored on fresh creation
        // (no saved state) so a rotation can't override the user's later choice.
        if (savedInstanceState == null) {
            when (arguments?.getString("filter")) {
                // Sentry clips live in the Surveillance segment; proximity clips
                // are recorded by the dashcam encoder and live in the DASHCAM
                // segment (the Surveillance segment filters to RecordingType.SENTRY
                // only, so proximity would land on an empty list there).
                "sentry" -> currentSource = Source.SURVEILLANCE
                "proximity", "normal" -> currentSource = Source.DASHCAM
                "replay" -> currentSource = Source.REPLAYS
            }
            // Parking page → "Open events": narrow to that park's clips. Same
            // token rule as the daemon so a mangled value can't reach the API.
            arguments?.getString("parkingSessionId")?.trim()
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_\\-]{1,64}")) }
                ?.let { parkingSessionId = it }
        }

        metricsExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "RecordingsMetrics").apply { isDaemon = true }
        }

        skeleton = Skeleton(view).apply {
            bind(R.id.tvRecordingsSummarySkeleton, view.findViewById(R.id.tvRecordingsSummary))
        }
        attachLibraryFragment()
        setupSegmentedControl(view)
        setupSettingsAction(view)
        setupDateRow(view)
        setupChipFilters(view)
        setupPlaceSearch(view)
        setupResetButton(view)
        setupParkingChip(view)
        setupLibraryActions(view)
        setupSelectionBar(view)
        setupPreviewActions(view)
        setupTimelineMode(view, savedInstanceState)

        updateDateHeader(view)
        renderActiveFilterAffordances(view)
        // Initial render with whatever availablePlaces we have (may be
        // empty on first mount — refreshCounts() populates the set
        // asynchronously and re-renders when it lands).
        renderPlaceChips(view)
        refreshCounts()

        // Back-press: collapse fullscreen first when active. The callback is
        // disabled until the user maximizes, so it adds no overhead in the
        // common case.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            fullscreenBackCallback
        )

        if (isLandscape) {
            // Re-bind a restored landscape player to its new host view.
            rebindActiveInlinePlayer()
        } else {
            // Portrait deliberately closes inline playback. Removal is posted
            // so child FragmentManager restoration can finish first.
            closeRestoredInlinePlayerInPortrait(view)
        }
    }

    override fun onResume() {
        super.onResume()
        // Drop any stale scan-cache the dashboard or another surface left
        // behind. Without this, refreshCounts() and the library reload could
        // both serve the same cached snapshot up to 5s after returning to
        // this page — including any cache populated before a parser bug fix.
        com.overdrive.app.ui.util.RecordingScanner.invalidateCache()
        // Refresh counts every time the user returns to this page so the
        // header reflects any new captures since the last visit.
        refreshCounts()
        // Bounce the library so it re-scans (handles new clips captured while
        // the page was hidden).
        libraryFragment?.let { applyAllFiltersTo(it) }
        if (!isLandscape) {
            view?.let(::closeRestoredInlinePlayerInPortrait)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            view?.let(::closeRestoredInlinePlayerInPortrait)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SOURCE, currentSource.name)
        outState.putStringArray(KEY_ACTORS, actorClassFilter.toTypedArray())
        outState.putStringArray(KEY_SEVERITY, severityFilter.toTypedArray())
        outState.putStringArray(KEY_DASHCAM_TYPES, dashcamTypes.toTypedArray())
        outState.putStringArray(KEY_PLACES, placeFilter.toTypedArray())
        outState.putStringArray(KEY_STORAGE, storageFilter.toTypedArray())
        outState.putInt(KEY_DATE_Y, calendar.get(Calendar.YEAR))
        outState.putInt(KEY_DATE_M, calendar.get(Calendar.MONTH))
        outState.putInt(KEY_DATE_D, selectedDay)
        outState.putBoolean(KEY_DATE_NARROWED, dateNarrowed)
        outState.putBoolean(KEY_PLAYER_FULLSCREEN, playerFullscreen)
        outState.putString(KEY_PLACE_CONTAINS, placeContainsQuery)
        outState.putString(KEY_PARKING_SESSION, parkingSessionId)
        outState.putBoolean(KEY_TIMELINE_MODE, timelineMode)
    }

    override fun onDestroyView() {
        filterSheet?.dismiss()
        filterSheet = null
        synchronized(pendingPosts) {
            pendingPosts.forEach { mainHandler.removeCallbacks(it) }
            pendingPosts.clear()
        }
        // Cancel any pending place-search debounce + warming-state retry
        // that's still queued on the main handler. Without this a delayed
        // post could fire after the view is gone and either NPE or push a
        // stale filter into a half-torn-down library fragment.
        placeSearchRunnable?.let { mainHandler.removeCallbacks(it) }
        placeSearchRunnable = null
        warmingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        warmingRetryRunnable = null
        libraryFragment?.apply {
            onPlayRecording = null
            onListChanged = null
            onContentChanged = null
            onClearAllFiltersRequested = null
            selectionHost = null
        }
        (childFragmentManager.findFragmentByTag(TAG_INLINE_PLAYER) as? VideoPlayerFragment)?.apply {
            onFullscreenToggle = null
            onPlaylistItemChanged = null
            onDurationResolved = null
        }
        timelineFragment()?.onDayChanged = null
        timelineHiddenViews.clear()
        libraryFragment = null
        skeleton?.cancel()
        skeleton = null
        metricsExecutor?.shutdownNow()
        metricsExecutor = null
        super.onDestroyView()
    }

    // -----------------------------------------------------------------
    // Library wiring
    // -----------------------------------------------------------------

    /**
     * Mount the embedded library fragment once. Same instance survives
     * segment switches; we drive it via [applyAllFiltersTo] which atomically
     * pushes every filter dimension and triggers exactly one reload.
     */
    private fun attachLibraryFragment() {
        val existing = childFragmentManager
            .findFragmentById(R.id.libraryContainer) as? RecordingLibraryFragment
        val lib = existing ?: RecordingLibraryFragment.newInstanceEmbedded().also {
            childFragmentManager.commit {
                replace(R.id.libraryContainer, it)
            }
        }
        libraryFragment = lib

        lib.onPlayRecording = if (isLandscape) {
            { recording -> showInlinePreview(recording) }
        } else {
            null
        }
        lib.onListChanged = { list ->
            currentPlaylist = list
            val activePath = activeInlinePath
            if (activePath != null) {
                currentInlineIndex = list.indexOfFirst {
                    it.path == activePath || it.playbackSource == activePath
                }
                if (currentInlineIndex >= 0) {
                    val active = list[currentInlineIndex]
                    selectedInlineRecording = active
                    bindPreviewDetails(active)
                    lib.setActiveRecording(active.path)
                } else {
                    activeInlinePath = null
                    selectedInlineRecording = null
                    lib.setActiveRecording(null)
                    showPreviewPlaceholder()
                }
            } else if (currentInlineIndex >= 0) {
                currentInlineIndex = -1
            }
        }
        // Library content changed (delete / batch-delete) — re-scan to
        // refresh the chip-derivation set + segment counters. Distinct
        // from `onListChanged` which fires on every filter pass /
        // re-render and would cause a redundant disk walk on every chip
        // tap.
        lib.onContentChanged = {
            refreshCounts()
        }
        // Empty-state CTA handler. The library's local actor/severity
        // are already cleared inside the library; here we additionally
        // reset every dimension the parent owns: type, place, and date.
        lib.onClearAllFiltersRequested = {
            actorClassFilter.clear()
            severityFilter.clear()
            dashcamTypes.clear()
            placeFilter.clear()
            storageFilter.clear()
            placeContainsQuery = ""
            parkingSessionId = ""
            dateNarrowed = false
            view?.let {
                syncChipChecks(it)
                renderPlaceChips(it)
                clearPlaceSearchInput(it)
                updateDateHeader(it)
                renderActiveFilterAffordances(it)
                applyAllFiltersTo(lib)
            }
        }
        applyAllFiltersTo(lib)
    }

    /**
     * Push every filter dimension into the library in one atomic call.
     *
     * Dashcam shows BOTH normal continuous-record clips and proximity-radar
     * clips — both come from the dashcam encoder, just triggered differently.
     * Surveillance is the standalone surveillance pipeline.
     *
     * Proximity clips carry actor/severity sidecars (radar detected something
     * + classified it), so the chip filters are kept active even in Dashcam
     * mode — they only narrow the proximity subset and pass cam_* clips
     * through untouched (they have no sidecar fields, so the filter logic
     * naturally excludes them when chips are non-empty — that's intentional).
     */
    private fun applyAllFiltersTo(library: RecordingLibraryFragment) {
        val source: RecordingLibraryFragment.RecordingFilter
        val extra: RecordingLibraryFragment.RecordingFilter?
        val actors: Set<String>
        val severities: Set<String>
        when (currentSource) {
            Source.ALL -> {
                source = RecordingLibraryFragment.RecordingFilter.ALL
                extra = null
                actors = emptySet()
                severities = emptySet()
            }
            Source.DASHCAM -> {
                // Derive type narrowing from the user's chip toggles. Empty
                // OR both = show NORMAL + PROXIMITY (the default Dashcam
                // experience). Single chip = narrow to that type only.
                val wantsNormal = dashcamTypes.contains("NORMAL")
                val wantsProximity = dashcamTypes.contains("PROXIMITY")
                when {
                    !wantsNormal && !wantsProximity -> {
                        source = RecordingLibraryFragment.RecordingFilter.NORMAL
                        extra = RecordingLibraryFragment.RecordingFilter.PROXIMITY
                    }
                    wantsNormal && wantsProximity -> {
                        source = RecordingLibraryFragment.RecordingFilter.NORMAL
                        extra = RecordingLibraryFragment.RecordingFilter.PROXIMITY
                    }
                    wantsNormal -> {
                        source = RecordingLibraryFragment.RecordingFilter.NORMAL
                        extra = null
                    }
                    else -> {
                        source = RecordingLibraryFragment.RecordingFilter.PROXIMITY
                        extra = null
                    }
                }
                // Dashcam segment hides the actor/severity rows, so push
                // empty sets — otherwise stale state from a previous
                // Surveillance session would silently filter Dashcam clips.
                actors = emptySet()
                severities = emptySet()
            }
            Source.REPLAYS -> {
                // Instant replays only. No type chips (single type), and no
                // actor/severity narrowing — replay clips are manual exports
                // with no surveillance classification sidecar.
                source = RecordingLibraryFragment.RecordingFilter.REPLAY
                extra = null
                actors = emptySet()
                severities = emptySet()
            }
            Source.SURVEILLANCE -> {
                source = RecordingLibraryFragment.RecordingFilter.SENTRY
                extra = null
                actors = actorClassFilter.toSet()
                severities = severityFilter.toSet()
            }
        }
        library.applyAll(
            source = source,
            actorClasses = actors,
            severity = severities,
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH),
            day = selectedDay,
            extraSource = extra,
            narrowToDate = dateNarrowed,
            places = placeFilter.toSet(),
            placeContains = placeContainsQuery.takeIf { it.isNotEmpty() },
            storages = storageFilter.toSet(),
            parkingSessionId = parkingSessionId.takeIf { it.isNotEmpty() }
        )
    }

    // -----------------------------------------------------------------
    // Inline player
    // -----------------------------------------------------------------

    /**
     * Replace the right-pane preview placeholder with an inline
     * VideoPlayerFragment for the given recording. Uses a soft fade
     * (300ms in / 200ms out) for a non-jarring swap between recordings.
     *
     * Only called on landscape — guarded by [isLandscape] in
     * [attachLibraryFragment]. The target container only exists in
     * `layout-land/fragment_recordings.xml`, so this is also a structural
     * safeguard against accidental portrait invocation.
     */
    private fun showInlinePreview(recording: RecordingFile) {
        val view = view ?: return
        val hostContext = context ?: return
        if (view.findViewById<View>(R.id.previewContainer) == null) return

        activeInlinePath = recording.playbackSource
        selectedInlineRecording = recording
        bindPreviewDetails(recording)
        libraryFragment?.setActiveRecording(recording.path)

        // Keep currentInlineIndex as an index into the descending currentPlaylist
        // (the on-screen list order) — onListChanged relies on that to track the
        // playing clip across re-renders.
        currentInlineIndex = currentPlaylist.indexOfFirst { it.path == recording.path }
        // The player treats index+1 as "next"/auto-advance, so hand it the
        // playlist in chronological order (oldest->newest). "Next" then plays
        // the NEWER clip while the library RecyclerView stays newest-first.
        val ordered = currentPlaylist.asReversed()
        val paths = ordered.map { it.playbackSource }.toTypedArray()
        val titles = ordered.map { RecordingUiText.headline(hostContext, it) }.toTypedArray()
        val playerIndex = ordered.indexOfFirst { it.path == recording.path }

        val player = VideoPlayerFragment().apply {
            arguments = Bundle().apply {
                putString(VideoPlayerFragment.ARG_VIDEO_PATH, recording.playbackSource)
                putString(
                    VideoPlayerFragment.ARG_VIDEO_TITLE,
                    RecordingUiText.headline(hostContext, recording)
                )
                putBoolean(VideoPlayerFragment.ARG_INLINE, true)
                putStringArray(VideoPlayerFragment.ARG_PLAYLIST_PATHS, paths)
                putStringArray(VideoPlayerFragment.ARG_PLAYLIST_TITLES, titles)
                putInt(
                    VideoPlayerFragment.ARG_PLAYLIST_INDEX,
                    playerIndex.coerceAtLeast(0)
                )
            }
            isFullscreen = playerFullscreen
            onFullscreenToggle = { wantFullscreen ->
                setPlayerFullscreen(wantFullscreen)
            }
            onPlaylistItemChanged = ::onInlinePlayerClipChanged
            onDurationResolved = ::onInlinePlayerDurationResolved
        }
        childFragmentManager.commit {
            setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
            replace(R.id.previewContainer, player, TAG_INLINE_PLAYER)
        }
    }

    /**
     * Re-attach host callbacks to a player that's already mounted (typical
     * after a configuration change). Without this, the maximize button on
     * the restored fragment would be hidden because the host's callback
     * reference was lost when the previous host view was destroyed.
     */
    private fun rebindActiveInlinePlayer() {
        val player = childFragmentManager
            .findFragmentByTag(TAG_INLINE_PLAYER) as? VideoPlayerFragment
            ?: return
        activeInlinePath = player.arguments?.getString(VideoPlayerFragment.ARG_VIDEO_PATH)
        activeInlinePath
            ?.let { source ->
                currentPlaylist.firstOrNull {
                    it.path == source || it.playbackSource == source
                }
            }
            ?.let {
                selectedInlineRecording = it
                bindPreviewDetails(it)
                libraryFragment?.setActiveRecording(it.path)
            }
        player.isFullscreen = playerFullscreen
        player.onFullscreenToggle = { wantFullscreen ->
            setPlayerFullscreen(wantFullscreen)
        }
        player.onPlaylistItemChanged = ::onInlinePlayerClipChanged
        player.onDurationResolved = ::onInlinePlayerDurationResolved
        // Re-apply the host-side layout to whatever state we restored into.
        view?.let { applyFullscreenLayout(it, playerFullscreen, animate = false) }
        fullscreenBackCallback.isEnabled = playerFullscreen
    }

    private fun onInlinePlayerClipChanged(source: String) {
        activeInlinePath = source
        val index = currentPlaylist.indexOfFirst {
            it.path == source || it.playbackSource == source
        }
        if (index < 0) return
        currentInlineIndex = index
        val recording = currentPlaylist[index]
        selectedInlineRecording = recording
        bindPreviewDetails(recording)
        libraryFragment?.setActiveRecording(recording.path)
    }

    private fun onInlinePlayerDurationResolved(source: String, durationMs: Long) {
        if (durationMs <= 0 || activeInlinePath != source) return
        val recording = selectedInlineRecording ?: return
        view?.findViewById<TextView>(R.id.tvPreviewDurationValue)?.text =
            recording.copy(durationMs = durationMs).formattedDuration
    }

    private fun bindPreviewDetails(recording: RecordingFile) {
        val root = view ?: return
        val hostContext = context ?: return
        root.findViewById<View>(R.id.previewPlaceholder)?.visibility = View.GONE
        root.findViewById<View>(R.id.previewContent)?.visibility = View.VISIBLE

        root.findViewById<TextView>(R.id.tvPreviewTitle)?.text =
            RecordingUiText.headline(hostContext, recording)
        root.findViewById<TextView>(R.id.tvPreviewFilename)?.text = recording.name
        root.findViewById<TextView>(R.id.tvPreviewTypeBadge)?.apply {
            text = when (recording.type) {
                RecordingFile.RecordingType.NORMAL -> getString(R.string.recording_lib_type_normal)
                RecordingFile.RecordingType.SENTRY -> getString(R.string.recording_lib_type_event)
                RecordingFile.RecordingType.PROXIMITY ->
                    getString(R.string.recording_lib_type_proximity)
                RecordingFile.RecordingType.OEM_DASHCAM ->
                    getString(R.string.recording_lib_type_oem)
                RecordingFile.RecordingType.REPLAY -> getString(R.string.recording_lib_type_replay)
            }
            paintBadge(
                ColorUtils.setAlphaComponent(themeColor(PRIMARY_ATTR), TYPE_BADGE_FILL_ALPHA),
                themeColor(ON_PRIMARY_CONTAINER_ATTR)
            )
        }

        val severity = recording.peakSeverity?.uppercase(Locale.ROOT)
        root.findViewById<TextView>(R.id.tvPreviewSeverityBadge)?.apply {
            visibility = if (severity == null) View.GONE else View.VISIBLE
            text = severity.orEmpty()
            // Same tonal fill / status label pairing as the dashboard chips.
            val tone = when (severity) {
                "CRITICAL" -> R.color.overdrive_status_danger_container to
                    R.color.overdrive_status_danger
                "ALERT" -> R.color.overdrive_status_warning_container to
                    R.color.overdrive_status_warning
                else -> null
            }
            if (tone == null) {
                paintBadge(themeColor(SURFACE_HIGHEST_ATTR), themeColor(ON_SURFACE_VARIANT_ATTR))
            } else {
                paintBadge(
                    ContextCompat.getColor(hostContext, tone.first),
                    ContextCompat.getColor(hostContext, tone.second)
                )
            }
        }
        root.findViewById<TextView>(R.id.tvPreviewDetectedValue)?.text =
            RecordingUiText.actorSummary(hostContext, recording)
                ?: getString(R.string.recording_preview_no_detection)
        root.findViewById<TextView>(R.id.tvPreviewDistanceValue)?.text =
            RecordingUiText.distanceLabel(hostContext, recording)
                ?: getString(R.string.recording_preview_not_available)
        root.findViewById<TextView>(R.id.tvPreviewRecordedValue)?.text =
            SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())
                .format(Date(recording.timestamp))
        root.findViewById<TextView>(R.id.tvPreviewDurationValue)?.text =
            recording.formattedDuration.takeIf { recording.durationMs > 0 }
                ?: getString(R.string.recording_preview_not_available)
        root.findViewById<TextView>(R.id.tvPreviewStorageValue)?.text =
            when (recording.storageType?.uppercase(Locale.ROOT)) {
                "INTERNAL" -> getString(R.string.recording_preview_storage_internal)
                "SD_CARD" -> getString(R.string.recording_preview_storage_sd)
                "USB" -> getString(R.string.recording_preview_storage_usb)
                else -> getString(R.string.recording_preview_not_available)
            }
        root.findViewById<TextView>(R.id.tvPreviewSizeValue)?.text = recording.formattedSize
    }

    private fun showPreviewPlaceholder() {
        view?.findViewById<View>(R.id.previewContent)?.visibility = View.GONE
        view?.findViewById<View>(R.id.previewPlaceholder)?.visibility = View.VISIBLE
    }

    private fun setPreviewDetailsExpanded(root: View, expanded: Boolean) {
        root.findViewById<View>(R.id.previewInsights)?.visibility =
            if (expanded) View.VISIBLE else View.GONE
        root.findViewById<ImageView>(R.id.ivPreviewInsightsChevron)?.setImageResource(
            if (expanded) R.drawable.ic_collapse else R.drawable.ic_expand
        )
        root.findViewById<View>(R.id.previewInsightsToggle)?.contentDescription = getString(
            if (expanded) R.string.cd_collapse_recording_details
            else R.string.cd_expand_recording_details
        )
    }

    private fun setupPreviewActions(root: View) {
        setPreviewDetailsExpanded(root, false)
        root.findViewById<View>(R.id.previewInsightsToggle)?.setOnClickListener {
            val expanded =
                root.findViewById<View>(R.id.previewInsights)?.visibility == View.VISIBLE
            setPreviewDetailsExpanded(root, !expanded)
        }

        // The preview overflow is the full actions menu (share, evidence pack, SafeKeep, delete).
        root.findViewById<View>(R.id.btnPreviewMore)?.setOnClickListener { anchor ->
            val recording = selectedInlineRecording ?: return@setOnClickListener
            libraryFragment?.showRecordingActions(anchor, recording) {
                selectedInlineRecording?.path == recording.path
            }
        }
        // Rounded inline video; applyFullscreenLayout drops the clip while maximised.
        root.findViewById<View>(R.id.previewContainer)?.clipToOutline = true
    }

    /**
     * Remove an inline player restored from a landscape child-fragment state
     * after the parent recreates with the portrait layout.
     *
     * The portrait XML keeps a zero-size [R.id.previewContainer] so
     * FragmentManager can complete restoration without throwing. The child
     * player independently refuses to prepare media without [R.id.twoPaneBody],
     * so this posted removal cannot produce hidden/background playback.
     */
    private fun closeRestoredInlinePlayerInPortrait(hostView: View) {
        if (isLandscape) return
        playerFullscreen = false
        currentInlineIndex = -1
        fullscreenBackCallback.isEnabled = false

        hostView.post {
            if (view !== hostView || isLandscape) return@post
            val manager = childFragmentManager
            val player = (manager.findFragmentByTag(TAG_INLINE_PLAYER)
                ?: manager.findFragmentById(R.id.previewContainer)) as? VideoPlayerFragment
                ?: return@post
            player.onFullscreenToggle = null
            runCatching {
                manager.beginTransaction()
                    .remove(player)
                    .commitNowAllowingStateLoss()
            }.onFailure {
                // If FragmentManager is still executing its restore pass,
                // enqueue removal for the next safe transaction boundary.
                runCatching {
                    manager.beginTransaction()
                        .remove(player)
                        .commitAllowingStateLoss()
                }
            }
        }
    }

    /**
     * Authoritative entry point for swapping fullscreen state. Animates the
     * surrounding chrome away (or back), pushes the new state into the
     * player so its icon flips, and arms/disarms the back-press intercept.
     *
     * No-ops when not landscape, or when there's no active inline player —
     * fullscreen is meaningless without something to fill the screen with.
     */
    private fun setPlayerFullscreen(fullscreen: Boolean) {
        val rootView = view ?: return
        if (!isLandscape) return
        val player = childFragmentManager
            .findFragmentByTag(TAG_INLINE_PLAYER) as? VideoPlayerFragment
        if (fullscreen && player == null) return
        if (playerFullscreen == fullscreen) return

        playerFullscreen = fullscreen
        applyFullscreenLayout(rootView, fullscreen, animate = true)
        player?.isFullscreen = fullscreen
        fullscreenBackCallback.isEnabled = fullscreen
    }

    /**
     * Hide/show the hero header, filter strip, and library pane to give the
     * preview surface the full screen — or restore the two-pane layout.
     *
     * Implementation: we GONE the chrome views (header + filter strip + the
     * library column + its spacer) so the remaining preview card stretches
     * naturally via the parent's match_parent constraints. A short alpha
     * crossfade smooths the swap; instant when [animate] is false (used
     * during configuration restore).
     *
     * The library column is collapsed by zeroing its weight rather than
     * GONE-ing the FragmentContainerView so its hosted fragment isn't
     * re-attached on collapse — list scroll position survives the toggle.
     */
    private fun applyFullscreenLayout(rootView: View, fullscreen: Boolean, animate: Boolean) {
        val header = rootView.findViewById<View>(R.id.heroHeader)
        val filterStrip = rootView.findViewById<View>(R.id.filterStrip)
        val library = rootView.findViewById<View>(R.id.libraryContainer)
        val spacer = rootView.findViewById<View>(R.id.paneSpacer)
        val twoPane = rootView.findViewById<View>(R.id.twoPaneBody)
        val previewCard = rootView.findViewById<com.google.android.material.card.MaterialCardView>(
            R.id.previewCard
        )
        val previewHeader = rootView.findViewById<View>(R.id.previewContentHeader)
        val previewInsightsToggle = rootView.findViewById<View>(R.id.previewInsightsToggle)
        val previewContent = rootView.findViewById<View>(R.id.previewContent)
        // The chrome views only exist on the landscape layout; portrait
        // would have already short-circuited in [setPlayerFullscreen].
        if (header == null || filterStrip == null || library == null) return

        val animDuration = if (animate) 220L else 0L

        fun fadeAndToggle(v: View, show: Boolean) {
            v.animate().cancel()
            if (show) {
                v.visibility = View.VISIBLE
                if (animate) {
                    v.alpha = 0f
                    v.animate().alpha(1f).setDuration(animDuration).start()
                } else {
                    v.alpha = 1f
                }
            } else {
                if (animate) {
                    v.animate().alpha(0f).setDuration(animDuration)
                        .withEndAction { v.visibility = View.GONE }
                        .start()
                } else {
                    v.alpha = 0f
                    v.visibility = View.GONE
                }
            }
        }

        fadeAndToggle(header, !fullscreen)
        fadeAndToggle(filterStrip, !fullscreen)

        // Collapse the library column without detaching its fragment.
        val params = library.layoutParams as? LinearLayout.LayoutParams
        if (params != null) {
            params.weight = if (fullscreen) 0f else LIBRARY_PANE_WEIGHT
            params.width = 0
            library.layoutParams = params
        }
        spacer?.visibility = if (fullscreen) View.GONE else View.VISIBLE
        if (animate) {
            library.animate().alpha(if (fullscreen) 0f else 1f)
                .setDuration(animDuration).start()
        } else {
            library.alpha = if (fullscreen) 0f else 1f
        }

        // Push the preview to true edge-to-edge: drop the page padding +
        // card corner radius while fullscreen, restore both on collapse.
        if (twoPane != null) {
            val pad = if (fullscreen) 0 else
                resources.getDimensionPixelSize(R.dimen.od_page_padding_h)
            val padTop = if (fullscreen) 0 else
                resources.getDimensionPixelSize(R.dimen.rec_body_padding_top)
            val padBottom = if (fullscreen) 0 else
                resources.getDimensionPixelSize(R.dimen.rec_body_padding_bottom)
            twoPane.setPadding(pad, padTop, pad, padBottom)
        }
        previewContent?.let {
            val pad = if (fullscreen) 0 else
                resources.getDimensionPixelSize(R.dimen.rec_preview_padding)
            it.setPadding(pad, pad, pad, pad)
        }
        previewCard?.radius = if (fullscreen) 0f else
            resources.getDimension(R.dimen.od_radius_card)
        val videoFrame = rootView.findViewById<View>(R.id.previewContainer)
        if (videoFrame != null) {
            videoFrame.clipToOutline = !fullscreen
            if (fullscreen) {
                videoFrame.background = ColorDrawable(Color.BLACK)
            } else {
                videoFrame.setBackgroundResource(R.drawable.rec_video_frame)
            }
        }
        previewHeader?.visibility = if (fullscreen) View.GONE else View.VISIBLE
        previewInsightsToggle?.visibility = if (fullscreen) View.GONE else View.VISIBLE
        if (fullscreen) setPreviewDetailsExpanded(rootView, false)
    }

    // -----------------------------------------------------------------
    // Top-bar controls
    // -----------------------------------------------------------------

    private fun setupSegmentedControl(view: View) {
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.segmentedSource)
        when (currentSource) {
            Source.ALL -> group.check(R.id.segmentAll)
            Source.DASHCAM -> group.check(R.id.segmentDashcam)
            Source.REPLAYS -> group.check(R.id.segmentReplays)
            Source.SURVEILLANCE -> group.check(R.id.segmentSurveillance)
        }
        applyChipRowVisibility(view)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentSource = when (checkedId) {
                R.id.segmentAll -> Source.ALL
                R.id.segmentSurveillance -> Source.SURVEILLANCE
                R.id.segmentReplays -> Source.REPLAYS
                else -> Source.DASHCAM
            }
            applyChipRowVisibility(view)
            // Reset button visibility depends on which segment we're on
            // (Dashcam = dashcamTypes, Surveillance = actor/severity).
            renderActiveFilterAffordances(view)
            // Place chip set is now segment-scoped — a switch could
            // surface different chips and possibly invalidate the
            // active place filter. Trigger a counts refresh so the
            // executor re-derives availablePlaces under the new
            // sourceAtDispatch; the post's stale-cleanup will clear
            // any place no longer in the new segment.
            refreshCounts()
            libraryFragment?.let { applyAllFiltersTo(it) }
        }
    }

    /**
     * Per-segment quick-chip visibility inside the single compact filter
     * row (both orientations host the chip sections in one horizontal
     * scroller on the date row / Row C):
     *  - Dashcam: only the Type section (Normal / Proximity) is visible.
     *    The What + Severity sections make no sense for continuous-record
     *    cam_* clips (no actor classification), so they're hidden.
     *  - Replays: no quick-filter chips (pre-record ring clips carry no
     *    type/actor/severity metadata).
     *  - Surveillance: What + Severity sections are visible, Type hidden.
     *
     * The Place section ([R.id.rowPlaceFilter]) is owned by
     * [renderPlaceChips] — visible only when geocoded clips exist.
     *
     * Storage narrowing stays sheet-only — it's an advanced, rarely-used
     * dimension and the inline strip is deliberately compact so the
     * library grid / player get the height.
     */
    private fun applyChipRowVisibility(view: View) {
        val isDashcam = currentSource == Source.DASHCAM
        val isSurveillance = currentSource == Source.SURVEILLANCE
        view.findViewById<View>(R.id.rowTypeFilter)?.visibility =
            if (isDashcam) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.rowWhatFilter)?.visibility =
            if (isSurveillance) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.scrollSeverityChips)?.visibility =
            if (isSurveillance) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.rowStorageFilter)?.visibility = View.GONE
        // Place chips are segment-scoped; re-render so their section
        // visibility tracks the availablePlaces set for the new segment.
        renderPlaceChips(view)
    }

    private fun setupLibraryActions(view: View) {
        view.findViewById<View>(R.id.btnSelectRecordings)?.setOnClickListener {
            libraryFragment?.enterSelectionMode()
        }
        view.findViewById<View>(R.id.btnOpenRecordingFilters)?.setOnClickListener {
            openRecordingFilters()
        }
    }

    /**
     * Landscape header hosts the selection bar in place of row 1; keyed on the bar, which only the
     * landscape layout has. Elsewhere the library keeps its own bar.
     */
    private fun setupSelectionBar(view: View) {
        val bar = view.findViewById<View>(R.id.recSelectionBar) ?: return
        val headerRow = view.findViewById<View>(R.id.recHeaderRow) ?: return
        val count = view.findViewById<TextView>(R.id.tvRecSelectionCount)
        val actions = listOfNotNull(
            view.findViewById<View>(R.id.btnRecSelectionAll),
            view.findViewById<View>(R.id.btnRecSelectionShare),
            view.findViewById<View>(R.id.btnRecSelectionDelete)
        )
        view.findViewById<View>(R.id.btnRecSelectionCancel)?.setOnClickListener {
            libraryFragment?.exitSelectionFromHost()
        }
        view.findViewById<View>(R.id.btnRecSelectionAll)?.setOnClickListener {
            libraryFragment?.selectAllFromHost()
        }
        view.findViewById<View>(R.id.btnRecSelectionShare)?.setOnClickListener {
            libraryFragment?.shareSelectionFromHost()
        }
        view.findViewById<View>(R.id.btnRecSelectionDelete)?.setOnClickListener {
            libraryFragment?.deleteSelectionFromHost()
        }
        libraryFragment?.selectionHost =
            RecordingLibraryFragment.SelectionHost { active, selected, enabled ->
                bar.visibility = if (active) View.VISIBLE else View.GONE
                headerRow.visibility = if (active) View.INVISIBLE else View.VISIBLE
                count?.text = bar.resources.getString(R.string.recording_lib_selected_count, selected)
                actions.forEach {
                    it.isEnabled = enabled
                    it.alpha = if (enabled) 1f else DISABLED_ACTION_ALPHA
                }
            }
    }

    private fun openRecordingFilters() {
        val ctx = context ?: return
        // One sheet at a time; a second tap while it opens is ignored.
        if (filterSheet?.isShowing == true) return
        // The two-pane body exists only in the landscape layout, which uses the end-edge side sheet.
        val sideSheet = view?.findViewById<View>(R.id.twoPaneBody) != null
        val sheet: AppCompatDialog = if (sideSheet) {
            SideSheetDialog(ctx, R.style.ThemeOverlay_Overdrive_Rec_SideSheet)
        } else {
            BottomSheetDialog(ctx, R.style.Theme_Overdrive_M3_BottomSheet)
        }
        val content = LayoutInflater.from(ctx)
            .inflate(R.layout.sheet_recording_library_filters, null, false)
        if (sideSheet) {
            content.findViewById<View>(R.id.sheetDragHandle)?.visibility = View.GONE
        } else {
            // M3 bottom sheets are edge-to-edge: keep Apply clear of the navigation bar.
            com.overdrive.app.ui.util.SheetInsets.padForNavigationBar(content)
        }
        sheet.setContentView(content)

        val localTypes = dashcamTypes.toMutableSet()
        val localActors = actorClassFilter.toMutableSet()
        val localSeverities = severityFilter.toMutableSet()
        val localStorages = storageFilter.toMutableSet()
        var localPlace = placeFilter.firstOrNull()
        var localParking = parkingSessionId
        var clearSearchOnApply = false

        val typeNormal = content.findViewById<Chip>(R.id.chipTypeNormal)
        val typeProximity = content.findViewById<Chip>(R.id.chipTypeProximity)
        val actorAny = content.findViewById<Chip>(R.id.chipActorAny)
        val actorPerson = content.findViewById<Chip>(R.id.chipActorPerson)
        val actorVehicle = content.findViewById<Chip>(R.id.chipActorVehicle)
        val actorBike = content.findViewById<Chip>(R.id.chipActorBike)
        val actorAnimal = content.findViewById<Chip>(R.id.chipActorAnimal)
        val severityAny = content.findViewById<Chip>(R.id.chipSevAny)
        val severityAlert = content.findViewById<Chip>(R.id.chipSevAlert)
        val severityCritical = content.findViewById<Chip>(R.id.chipSevCritical)
        val storageAny = content.findViewById<Chip>(R.id.chipStorageAny)
        val storageInternal = content.findViewById<Chip>(R.id.chipStorageInternal)
        val storageSd = content.findViewById<Chip>(R.id.chipStorageSd)
        val storageUsb = content.findViewById<Chip>(R.id.chipStorageUsb)
        val parkingChip = content.findViewById<Chip>(R.id.sheetChipParking)

        fun syncChecks() {
            typeNormal.isChecked = "NORMAL" in localTypes
            typeProximity.isChecked = "PROXIMITY" in localTypes
            actorAny.isChecked = localActors.isEmpty()
            actorPerson.isChecked = "person" in localActors
            actorVehicle.isChecked = "vehicle" in localActors
            actorBike.isChecked = "bike" in localActors
            actorAnimal.isChecked = "animal" in localActors
            severityAny.isChecked = localSeverities.isEmpty()
            severityAlert.isChecked = "ALERT" in localSeverities
            severityCritical.isChecked = "CRITICAL" in localSeverities
            storageAny.isChecked = localStorages.isEmpty()
            storageInternal.isChecked = "INTERNAL" in localStorages
            storageSd.isChecked = "SD_CARD" in localStorages
            storageUsb.isChecked = "USB" in localStorages
            parkingChip?.isChecked = localParking.isNotEmpty()
        }

        fun toggle(set: MutableSet<String>, value: String) {
            if (!set.add(value)) set.remove(value)
            syncChecks()
        }

        typeNormal.setOnClickListener { toggle(localTypes, "NORMAL") }
        typeProximity.setOnClickListener { toggle(localTypes, "PROXIMITY") }
        actorAny.setOnClickListener { localActors.clear(); syncChecks() }
        actorPerson.setOnClickListener { toggle(localActors, "person") }
        actorVehicle.setOnClickListener { toggle(localActors, "vehicle") }
        actorBike.setOnClickListener { toggle(localActors, "bike") }
        actorAnimal.setOnClickListener { toggle(localActors, "animal") }
        severityAny.setOnClickListener { localSeverities.clear(); syncChecks() }
        severityAlert.setOnClickListener { toggle(localSeverities, "ALERT") }
        severityCritical.setOnClickListener { toggle(localSeverities, "CRITICAL") }
        storageAny.setOnClickListener { localStorages.clear(); syncChecks() }
        storageInternal.setOnClickListener { toggle(localStorages, "INTERNAL") }
        storageSd.setOnClickListener { toggle(localStorages, "SD_CARD") }
        storageUsb.setOnClickListener { toggle(localStorages, "USB") }
        // Parking: only the arrived-with session can be kept or dropped here.
        if (parkingSessionId.isNotEmpty()) {
            content.findViewById<View>(R.id.sheetParkingSection)?.visibility = View.VISIBLE
            parkingChip?.text = parkingSessionLabel(ctx, parkingSessionId)
            parkingChip?.setOnClickListener {
                localParking = if (localParking.isEmpty()) parkingSessionId else ""
                syncChecks()
            }
        }

        content.findViewById<View>(R.id.sheetTypeSection).visibility =
            if (currentSource == Source.DASHCAM) View.VISIBLE else View.GONE
        val surveillanceVisibility =
            if (currentSource == Source.SURVEILLANCE) View.VISIBLE else View.GONE
        content.findViewById<View>(R.id.sheetActorSection).visibility = surveillanceVisibility
        content.findViewById<View>(R.id.sheetSeveritySection).visibility = surveillanceVisibility

        val placeSection = content.findViewById<View>(R.id.sheetPlaceSection)
        val placeGroup = content.findViewById<ChipGroup>(R.id.sheetPlaceChipGroup)
        placeSection.visibility = if (availablePlaces.isEmpty()) View.GONE else View.VISIBLE
        val placeById = mutableMapOf<Int, String?>()
        fun addPlaceChip(label: String, key: String?) {
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = key == localPlace || (key == null && localPlace == null)
            }
            placeById[chip.id] = key
            placeGroup.addView(chip)
        }
        addPlaceChip(getString(R.string.recording_lib_chip_any), null)
        availablePlaces.forEach { addPlaceChip(it, it.lowercase()) }
        placeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            localPlace = checkedIds.firstOrNull()?.let(placeById::get)
        }

        content.findViewById<View>(R.id.btnFilterReset).setOnClickListener {
            localTypes.clear()
            localActors.clear()
            localSeverities.clear()
            localStorages.clear()
            localPlace = null
            localParking = ""
            clearSearchOnApply = true
            placeGroup.check(placeById.entries.first { it.value == null }.key)
            syncChecks()
        }
        content.findViewById<View>(R.id.btnFilterApply).setOnClickListener {
            dashcamTypes.clear()
            dashcamTypes.addAll(localTypes)
            actorClassFilter.clear()
            actorClassFilter.addAll(localActors)
            severityFilter.clear()
            severityFilter.addAll(localSeverities)
            storageFilter.clear()
            storageFilter.addAll(localStorages)
            placeFilter.clear()
            localPlace?.let(placeFilter::add)
            parkingSessionId = localParking
            if (clearSearchOnApply) {
                placeContainsQuery = ""
                view?.let(::clearPlaceSearchInput)
            }
            view?.let {
                syncChipChecks(it)
                renderPlaceChips(it)
                onFiltersChanged(it)
            }
            refreshCounts()
            sheet.dismiss()
        }
        content.findViewById<View>(R.id.btnFilterCancel)?.setOnClickListener { sheet.dismiss() }

        syncChecks()
        filterSheet = sheet
        sheet.setOnDismissListener { if (filterSheet === sheet) filterSheet = null }
        sheet.show()
    }

    private fun setupSettingsAction(view: View) {
        view.findViewById<MaterialButton>(R.id.btnRecordingsSettings)?.setOnClickListener {
            when (currentSource) {
                // Replays share the dashcam encoder knobs, so they open Settings
                // on the Recording section.
                Source.ALL, Source.DASHCAM, Source.REPLAYS -> {
                    val args = Bundle().apply {
                        putString(
                            SettingsFragment.KEY_SECTION,
                            SettingsFragment.SECTION_RECORDING
                        )
                        putBoolean(SettingsFragment.KEY_FROM_RECORDINGS, true)
                    }
                    findNavController().navigateDrillDown(R.id.settingsFragment, args)
                }
                Source.SURVEILLANCE ->
                    findNavController().navigate(R.id.surveillanceSettingsWebFragment)
            }
        }
        view.findViewById<MaterialButton>(R.id.btnFactoryDvr)?.setOnClickListener {
            findNavController().navigateDrillDown(R.id.factoryDvrFragment)
        }
    }

    private fun setupDateRow(view: View) {
        view.findViewById<MaterialCardView>(R.id.cardDateJump)?.setOnClickListener {
            showDatePicker()
        }
        view.findViewById<MaterialButton>(R.id.btnPrevDay)?.setOnClickListener {
            shiftSelectedDay(-1)
        }
        view.findViewById<MaterialButton>(R.id.btnNextDay)?.setOnClickListener {
            shiftSelectedDay(+1)
        }
        view.findViewById<ImageButton>(R.id.btnClearDate)?.setOnClickListener {
            // Drop the date narrowing without touching chip filters. Reset
            // is a "wipe everything" hammer; this is the surgical version
            // for the date dimension only.
            dateNarrowed = false
            updateDateHeader(view)
            renderActiveFilterAffordances(view)
            libraryFragment?.let { applyAllFiltersTo(it) }
        }
    }

    private fun setupChipFilters(view: View) {
        // WHAT (actor class) — multi-select. "Any" clears the row.
        val chipActorAny = view.findViewById<Chip>(R.id.chipActorAny)
        val chipPerson = view.findViewById<Chip>(R.id.chipActorPerson)
        val chipVehicle = view.findViewById<Chip>(R.id.chipActorVehicle)
        val chipBike = view.findViewById<Chip>(R.id.chipActorBike)
        val chipAnimal = view.findViewById<Chip>(R.id.chipActorAnimal)

        // SEVERITY — multi-select. "Any" clears the row.
        val chipSevAny = view.findViewById<Chip>(R.id.chipSevAny)
        val chipSevAlert = view.findViewById<Chip>(R.id.chipSevAlert)
        val chipSevCritical = view.findViewById<Chip>(R.id.chipSevCritical)

        // TYPE (Dashcam-only) — multi-select toggle. Empty selection = both.
        val chipTypeNormal = view.findViewById<Chip>(R.id.chipTypeNormal)
        val chipTypeProximity = view.findViewById<Chip>(R.id.chipTypeProximity)
        val typeMap = mapOf(
            chipTypeNormal to "NORMAL",
            chipTypeProximity to "PROXIMITY"
        )
        for ((chip, name) in typeMap) {
            chip?.setOnClickListener {
                if (dashcamTypes.contains(name)) dashcamTypes.remove(name)
                else dashcamTypes.add(name)
                syncChipChecks(view)
                onFiltersChanged(view)
            }
        }

        chipActorAny?.setOnClickListener {
            actorClassFilter.clear()
            syncChipChecks(view)
            onFiltersChanged(view)
        }
        val mapActor = mapOf(
            chipPerson to "person",
            chipVehicle to "vehicle",
            chipBike to "bike",
            chipAnimal to "animal"
        )
        for ((chip, name) in mapActor) {
            chip?.setOnClickListener {
                if (actorClassFilter.contains(name)) actorClassFilter.remove(name)
                else actorClassFilter.add(name)
                syncChipChecks(view)
                onFiltersChanged(view)
            }
        }

        chipSevAny?.setOnClickListener {
            severityFilter.clear()
            syncChipChecks(view)
            onFiltersChanged(view)
        }
        val mapSev = mapOf(
            chipSevAlert to "ALERT",
            chipSevCritical to "CRITICAL"
        )
        for ((chip, name) in mapSev) {
            chip?.setOnClickListener {
                if (severityFilter.contains(name)) severityFilter.remove(name)
                else severityFilter.add(name)
                syncChipChecks(view)
                onFiltersChanged(view)
            }
        }

        // STORAGE — multi-select toggle, "Any" clears the row. Shown in both
        // segments; narrows the (already cross-volume) library to one physical
        // location.
        view.findViewById<Chip>(R.id.chipStorageAny)?.setOnClickListener {
            storageFilter.clear()
            syncChipChecks(view)
            onFiltersChanged(view)
        }
        val mapStorage = mapOf(
            view.findViewById<Chip>(R.id.chipStorageInternal) to "INTERNAL",
            view.findViewById<Chip>(R.id.chipStorageSd) to "SD_CARD",
            view.findViewById<Chip>(R.id.chipStorageUsb) to "USB"
        )
        for ((chip, name) in mapStorage) {
            chip?.setOnClickListener {
                if (storageFilter.contains(name)) storageFilter.remove(name)
                else storageFilter.add(name)
                syncChipChecks(view)
                onFiltersChanged(view)
            }
        }

        syncChipChecks(view)
    }

    private fun syncChipChecks(view: View) {
        view.findViewById<Chip>(R.id.chipActorAny)?.isChecked = actorClassFilter.isEmpty()
        view.findViewById<Chip>(R.id.chipActorPerson)?.isChecked = actorClassFilter.contains("person")
        view.findViewById<Chip>(R.id.chipActorVehicle)?.isChecked = actorClassFilter.contains("vehicle")
        view.findViewById<Chip>(R.id.chipActorBike)?.isChecked = actorClassFilter.contains("bike")
        view.findViewById<Chip>(R.id.chipActorAnimal)?.isChecked = actorClassFilter.contains("animal")
        view.findViewById<Chip>(R.id.chipSevAny)?.isChecked = severityFilter.isEmpty()
        view.findViewById<Chip>(R.id.chipSevAlert)?.isChecked = severityFilter.contains("ALERT")
        view.findViewById<Chip>(R.id.chipSevCritical)?.isChecked = severityFilter.contains("CRITICAL")
        view.findViewById<Chip>(R.id.chipTypeNormal)?.isChecked = dashcamTypes.contains("NORMAL")
        view.findViewById<Chip>(R.id.chipTypeProximity)?.isChecked = dashcamTypes.contains("PROXIMITY")
        view.findViewById<Chip>(R.id.chipStorageAny)?.isChecked = storageFilter.isEmpty()
        view.findViewById<Chip>(R.id.chipStorageInternal)?.isChecked = storageFilter.contains("INTERNAL")
        view.findViewById<Chip>(R.id.chipStorageSd)?.isChecked = storageFilter.contains("SD_CARD")
        view.findViewById<Chip>(R.id.chipStorageUsb)?.isChecked = storageFilter.contains("USB")
    }

    private fun setupResetButton(view: View) {
        view.findViewById<MaterialButton>(R.id.btnFilterReset)?.setOnClickListener {
            // Reset only clears CHIP filters. The date has its own clear-X
            // affordance on the date card — bundling them confused users
            // because the card kept reading "Today" while the list silently
            // showed every day after Reset.
            actorClassFilter.clear()
            severityFilter.clear()
            dashcamTypes.clear()
            placeFilter.clear()
            storageFilter.clear()
            placeContainsQuery = ""
            parkingSessionId = ""
            syncChipChecks(view)
            renderPlaceChips(view)
            clearPlaceSearchInput(view)
            onFiltersChanged(view)
        }
    }

    /**
     * Parking Intelligence chip: visible only while [parkingSessionId] narrows
     * the list. Its close icon drops the narrowing (Reset does too). The label
     * shows the park's start time when the id carries one
     * ("park_yyyyMMdd_HHmmss"), else the generic "This park".
     */
    private fun setupParkingChip(view: View) {
        view.findViewById<Chip>(R.id.chipParkingSession)?.setOnCloseIconClickListener {
            parkingSessionId = ""
            onFiltersChanged(view)
        }
    }

    private fun renderParkingChip(view: View) {
        val row = view.findViewById<View>(R.id.rowParkingFilter) ?: return
        val chip = view.findViewById<Chip>(R.id.chipParkingSession) ?: return
        if (parkingSessionId.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        chip.text = parkingSessionLabel(view.context, parkingSessionId)
    }

    private fun parkingSessionLabel(ctx: android.content.Context, sessionId: String): String {
        val m = Regex("park_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})\\d{2}(?:_\\d+)?").matchEntire(sessionId)
            ?: return ctx.getString(R.string.recording_lib_chip_parking_session)
        val (y, mo, d, h, mi) = m.destructured
        val cal = Calendar.getInstance().apply {
            clear()
            set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt())
        }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(cal.time)
        val today = Calendar.getInstance()
        val sameDay = today.get(Calendar.YEAR) == cal.get(Calendar.YEAR)
            && today.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
        val label = if (sameDay) time
            else java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(cal.time) + " " + time
        return ctx.getString(R.string.recording_lib_chip_parking_session_at, label)
    }

    /**
     * Wire up the landscape free-text place-search input. Null-safe because
     * the EditText only exists in `layout-land/fragment_recordings.xml` —
     * portrait users still use the chip row exclusively.
     *
     * Debounce strategy: 300ms via [mainHandler] postDelayed so the API
     * isn't hit on every keystroke. Same Handler/Runnable pattern as the
     * rest of this file (no coroutines).
     */
    private fun setupPlaceSearch(view: View) {
        val editText = view.findViewById<EditText>(R.id.etPlaceSearch) ?: return
        val clearBtn = view.findViewById<ImageButton>(R.id.btnClearPlaceSearch)

        // Portrait collapses the search card behind a toggle icon so the
        // library grid gets the height back; landscape hosts the card in
        // the title row permanently and has no toggle (null-safe).
        val searchCard = view.findViewById<View>(R.id.cardPlaceSearch)
        view.findViewById<View>(R.id.btnTogglePlaceSearch)?.setOnClickListener {
            val card = searchCard ?: return@setOnClickListener
            if (card.visibility == View.VISIBLE) {
                // Collapsing counts as "dismiss": kill any PENDING debounce
                // unconditionally — a keystroke inside the 300ms window
                // hasn't reached placeContainsQuery yet, but its runnable
                // would otherwise fire after the card is gone and apply an
                // invisible filter. Then drop any already-active query.
                placeSearchRunnable?.let { mainHandler.removeCallbacks(it) }
                placeSearchRunnable = null
                if (!editText.text.isNullOrEmpty()) editText.setText("")
                if (placeContainsQuery.isNotEmpty()) {
                    placeContainsQuery = ""
                    renderActiveFilterAffordances(view)
                    libraryFragment?.let { applyAllFiltersTo(it) }
                    refreshCounts()
                }
                card.visibility = View.GONE
            } else {
                card.visibility = View.VISIBLE
                editText.requestFocus()
            }
        }
        // Auto-expand when a query is active (e.g. restored after rotation
        // or process death) so the narrowing is always visible.
        if (placeContainsQuery.isNotEmpty()) searchCard?.visibility = View.VISIBLE

        // Restore from saved state if we got rotated mid-search.
        if (placeContainsQuery.isNotEmpty() && editText.text.toString() != placeContainsQuery) {
            editText.setText(placeContainsQuery)
            editText.setSelection(editText.text.length)
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString().orEmpty().trim()
                // Cancel any prior pending fire — only the most recent
                // keystroke triggers an API call after the debounce.
                placeSearchRunnable?.let { mainHandler.removeCallbacks(it) }
                val pending = Runnable {
                    placeSearchRunnable = null
                    if (placeContainsQuery == raw) return@Runnable
                    placeContainsQuery = raw
                    val v = this@RecordingsFragment.view ?: return@Runnable
                    renderActiveFilterAffordances(v)
                    libraryFragment?.let { applyAllFiltersTo(it) }
                    // Re-derive the chip set under the new search — server's
                    // /api/recordings/places stacks placeContains on top of
                    // type so the chips reflect the remaining set.
                    refreshCounts()
                }
                placeSearchRunnable = pending
                mainHandler.postDelayed(pending, PLACE_SEARCH_DEBOUNCE_MS)
            }
        })

        clearBtn?.setOnClickListener {
            // Tap-to-clear: kill the debounce, blank the field, push
            // immediately so the user gets instant feedback.
            placeSearchRunnable?.let { mainHandler.removeCallbacks(it) }
            placeSearchRunnable = null
            editText.setText("")
            placeContainsQuery = ""
            renderActiveFilterAffordances(view)
            libraryFragment?.let { applyAllFiltersTo(it) }
            refreshCounts()
        }
    }

    /**
     * Blank the landscape search EditText without re-triggering the
     * TextWatcher's debounce path (we already cleared placeContainsQuery
     * synchronously). Null-safe for portrait.
     */
    private fun clearPlaceSearchInput(view: View) {
        val editText = view.findViewById<EditText>(R.id.etPlaceSearch) ?: return
        if (editText.text.isNullOrEmpty()) return
        editText.setText("")
        // Cancel any pending debounce that's about to fire with the now-
        // stale value — the caller has already mutated placeContainsQuery.
        placeSearchRunnable?.let { mainHandler.removeCallbacks(it) }
        placeSearchRunnable = null
    }

    private fun renderActiveFilterAffordances(view: View) {
        val searchActive = placeContainsQuery.isNotEmpty()
        // Storage and the parking session apply in BOTH segments, so they
        // contribute to the active-filter affordance regardless of the source.
        val storageActive = storageFilter.isNotEmpty()
        renderParkingChip(view)
        val chipsActive = storageActive || parkingSessionId.isNotEmpty() || when (currentSource) {
            Source.ALL -> placeFilter.isNotEmpty() || searchActive
            Source.DASHCAM -> dashcamTypes.size == 1 || placeFilter.isNotEmpty() || searchActive
            Source.REPLAYS -> placeFilter.isNotEmpty() || searchActive
            Source.SURVEILLANCE -> actorClassFilter.isNotEmpty()
                || severityFilter.isNotEmpty()
                || placeFilter.isNotEmpty()
                || searchActive
        }
        // Reset only governs chip filters now — the date has its own clear-X
        // on the date card.
        view.findViewById<MaterialButton>(R.id.btnFilterReset)?.visibility =
            if (chipsActive) View.VISIBLE else View.GONE
        val filterCount = activeSheetFilterCount()
        view.findViewById<View>(R.id.btnOpenRecordingFilters)?.isSelected = filterCount > 0
        view.findViewById<MaterialButton>(R.id.btnOpenRecordingFilters)
            ?.let { renderFilterCount(it, filterCount) }
        // Toggle the inline clear-X button on the search card so the user
        // can wipe the search without manually backspacing every char.
        view.findViewById<ImageButton>(R.id.btnClearPlaceSearch)?.visibility =
            if (searchActive) View.VISIBLE else View.GONE
    }

    private fun onFiltersChanged(view: View) {
        renderActiveFilterAffordances(view)
        libraryFragment?.let { applyAllFiltersTo(it) }
    }

    /** Filter-sheet sections narrowing the current segment (same rules as [applyAllFiltersTo]). */
    private fun activeSheetFilterCount(): Int {
        var count = 0
        when (currentSource) {
            // Both dashcam types checked is the same as none (see applyAllFiltersTo).
            Source.DASHCAM -> if (dashcamTypes.size == 1) count++
            Source.SURVEILLANCE -> {
                if (actorClassFilter.isNotEmpty()) count++
                if (severityFilter.isNotEmpty()) count++
            }
            Source.ALL, Source.REPLAYS -> Unit
        }
        if (placeFilter.isNotEmpty()) count++
        if (parkingSessionId.isNotEmpty()) count++
        if (storageFilter.isNotEmpty()) count++
        // The sheet's Reset clears the place search too, so it counts as a filter.
        if (placeContainsQuery.isNotEmpty()) count++
        return count
    }

    private fun renderFilterCount(button: MaterialButton, count: Int) {
        val label = getString(R.string.recording_lib_filter_button)
        if (count <= 0) {
            button.text = label
            button.contentDescription = getString(R.string.recording_library_open_filters)
            return
        }
        val ctx = button.context
        val digits = String.format(Locale.getDefault(), "%d", count)
        button.text = SpannableStringBuilder(label).append(' ').apply {
            append(
                digits,
                CountBadgeSpan(
                    fill = button.themeColor(PRIMARY_ATTR),
                    textColor = button.themeColor(ON_PRIMARY_ATTR),
                    textSizePx = ctx.resources.getDimension(R.dimen.rec_filter_badge_text),
                    sizePx = ctx.resources.getDimension(R.dimen.rec_filter_badge_size)
                ),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        button.contentDescription = getString(R.string.recording_lib_filter_button_active, count)
    }

    // -----------------------------------------------------------------
    // Date handling
    // -----------------------------------------------------------------

    private fun showDatePicker() {
        val constraints = CalendarConstraints.Builder()
            .setEnd(MaterialDatePicker.todayInUtcMilliseconds())
            .setValidator(DateValidatorPointBackward.now())
            .build()

        val selectedUtcMs = utcMidnightMillis(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            selectedDay
        )

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.recording_lib_pick_date)
            .setCalendarConstraints(constraints)
            .setSelection(selectedUtcMs)
            .build()

        picker.addOnPositiveButtonClickListener { utcMs ->
            val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = utcMs
            }
            calendar.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), 1)
            selectedDay = cal.get(Calendar.DAY_OF_MONTH)
            dateNarrowed = true
            view?.let { updateDateHeader(it) }
            view?.let { renderActiveFilterAffordances(it) }
            libraryFragment?.let { applyAllFiltersTo(it) }
        }

        picker.show(parentFragmentManager, "recording_lib_date_picker")
    }

    private fun utcMidnightMillis(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun shiftSelectedDay(delta: Int) {
        val cal = Calendar.getInstance().apply {
            set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), selectedDay)
            add(Calendar.DAY_OF_MONTH, delta)
        }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val candidate = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (candidate.after(today)) return

        calendar.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), 1)
        selectedDay = cal.get(Calendar.DAY_OF_MONTH)
        dateNarrowed = true
        view?.let { updateDateHeader(it) }
        view?.let { renderActiveFilterAffordances(it) }
        libraryFragment?.let { applyAllFiltersTo(it) }
    }

    private fun updateDateHeader(view: View) {
        val selectedCal = Calendar.getInstance().apply {
            set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), selectedDay, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val yesterday = (today.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }

        view.findViewById<TextView>(R.id.tvSelectedDate)?.text = when {
            !dateNarrowed -> getString(R.string.recording_lib_date_all_days)
            selectedCal.timeInMillis == today.timeInMillis ->
                getString(R.string.recording_lib_date_today)
            selectedCal.timeInMillis == yesterday.timeInMillis ->
                getString(R.string.recording_lib_date_yesterday)
            else -> dayHeaderFormat.format(Date(selectedCal.timeInMillis))
        }

        // Prev/next day chevrons only mean something while a single day is
        // selected. When the user is showing all days, hide them so they
        // can't accidentally jump into single-day mode by tapping ▶.
        view.findViewById<MaterialButton>(R.id.btnPrevDay)?.visibility =
            if (dateNarrowed) View.VISIBLE else View.GONE
        view.findViewById<MaterialButton>(R.id.btnNextDay)?.let {
            it.visibility = if (dateNarrowed) View.VISIBLE else View.GONE
            it.isEnabled = selectedCal.timeInMillis < today.timeInMillis
            it.alpha = if (it.isEnabled) 1f else 0.4f
        }

        // Clear-X is only relevant while narrowed; same goes for the count
        // pill, which is meaningless when the list is all days.
        view.findViewById<ImageButton>(R.id.btnClearDate)?.visibility =
            if (dateNarrowed) View.VISIBLE else View.GONE
    }

    // -----------------------------------------------------------------
    // Counts (executor + post)
    // -----------------------------------------------------------------

    /**
     * Hit the daemon's H2-backed `/api/recordings/stats` + `/api/recordings/places`
     * for the header counters and the segment-scoped place chip set.
     * Falls back to the unified [RecordingScanner] (which itself tries the
     * API first and only walks the FS as a last resort) if the daemon is
     * unreachable.
     *
     * Lifecycle-safe: HTTP calls run on [metricsExecutor]; UI posts gated
     * by [pendingPosts] so onDestroyView can drop them. While the daemon
     * reports `warming=true` we poll every 2s so the header lights up as
     * soon as the index lands.
     */
    private fun refreshCounts() {
        val ctx = context ?: return
        val executor = metricsExecutor ?: return
        val viewRef = WeakReference(view)
        // Snapshot the active segment NOW (on the UI thread) so the
        // executor's chip-bucket derivation scopes to the segment the
        // user has visible. Without this, the chips would be built
        // from the global recording set — the user could tap a place
        // chip that has no clips in the active segment and get an
        // empty list.
        val sourceAtDispatch = currentSource
        val placeContainsAtDispatch = placeContainsQuery

        executor.execute {
            // /api/recordings/stats — flat counters covering both segments.
            val stats = try { RecordingsApiClient.fetchStats() } catch (_: Throwable) { null }

            // Index down (daemon reachable, its index isn't): the counters are
            // unknown, not zero. Post the honest header instead of running the
            // direct-FS aggregate — that walk can't read SD/USB under the app
            // UID and would render an authoritative "0 today · 0 total · 0 B"
            // right above the library's own "index unavailable" card.
            if (stats != null && stats.indexUnavailable) {
                postCountsToUi(
                    viewRef = viewRef,
                    dashcamCount = 0,
                    replaysCount = 0,
                    surveillanceCount = 0,
                    totalCount = 0,
                    totalToday = 0,
                    totalBytes = 0,
                    sortedPlaces = availablePlaces,
                    warming = false,
                    indexDown = true
                )
                return@execute
            }

            // Fallback path: the daemon couldn't be reached. Use the unified
            // scanner (it owns its own API-then-FS fallback ladder) so we
            // don't duplicate the FS walk here.
            if (stats == null) {
                val today0 = startOfTodayMillis()
                val all = RecordingScanner.scanRecordings(ctx)
                val dashcam = all.filter {
                    it.type == RecordingFile.RecordingType.NORMAL ||
                        it.type == RecordingFile.RecordingType.PROXIMITY ||
                        it.type == RecordingFile.RecordingType.OEM_DASHCAM
                }
                val replays = all.filter { it.type == RecordingFile.RecordingType.REPLAY }
                val surveillance = all.filter { it.type == RecordingFile.RecordingType.SENTRY }
                val dashcamStats = aggregate(dashcam, today0)
                val replayStats = aggregate(replays, today0)
                val surveillanceStats = aggregate(surveillance, today0)
                val totalCount = dashcamStats.total + replayStats.total + surveillanceStats.total
                val totalToday = dashcamStats.today + replayStats.today + surveillanceStats.today
                val totalBytes = dashcamStats.bytes + replayStats.bytes + surveillanceStats.bytes
                val segmentClips = when (sourceAtDispatch) {
                    Source.ALL -> all
                    Source.DASHCAM -> dashcam
                    Source.REPLAYS -> replays
                    Source.SURVEILLANCE -> surveillance
                }
                val sortedPlaces = derivePlaceLabelsFromScan(segmentClips)
                postCountsToUi(
                    viewRef = viewRef,
                    dashcamCount = dashcamStats.total.toLong(),
                    replaysCount = replayStats.total.toLong(),
                    surveillanceCount = surveillanceStats.total.toLong(),
                    totalCount = totalCount.toLong(),
                    totalToday = totalToday.toLong(),
                    totalBytes = totalBytes,
                    sortedPlaces = sortedPlaces,
                    warming = false
                )
                return@execute
            }

            // Scope the chip set to the active segment + current search so
            // the user sees only places that have clips matching their
            // current view. Server stacks `type` and `placeContains` so we
            // don't have to re-filter client-side.
            val placesResult = try {
                RecordingsApiClient.fetchPlaces(
                    RecordingsApiClient.Filter(
                        type = sourceAtDispatch.toApiType(),
                        placeContains = placeContainsAtDispatch.takeIf { it.isNotEmpty() }
                    )
                )
            } catch (_: Throwable) { null }
            val sortedPlaces = placesResult
                ?.map { it.label }
                ?.take(MAX_PLACE_CHIPS)
                ?: emptyList()

            // Dashcam segment = NORMAL + PROXIMITY (both come off the AVM
            // dashcam encoder). The /stats payload doesn't carry an
            // oemDashcam counter explicitly, but server normalizes
            // type=normal to include OEM clips so they're already inside
            // normalCount. Sum normal + proximity for the segment badge.
            val dashcamCount = stats.normalCount + stats.proximityCount
            val replaysCount = stats.replayCount
            val surveillanceCount = stats.sentryCount

            postCountsToUi(
                viewRef = viewRef,
                dashcamCount = dashcamCount,
                replaysCount = replaysCount,
                surveillanceCount = surveillanceCount,
                totalCount = stats.totalCount,
                totalToday = stats.totalToday,
                totalBytes = stats.totalSize,
                sortedPlaces = sortedPlaces,
                warming = stats.warming
            )
        }
    }

    /**
     * Map a [Source] to the API's `type` parameter. Server-side
     * normalisation: type=normal includes oemDashcam clips, so the
     * Dashcam segment passes "normal" plus we sum normalCount + proximityCount
     * for the badge. Sentry is its own bucket.
     */
    private fun Source.toApiType(): String? = when (this) {
        Source.ALL -> null
        Source.DASHCAM -> "normal"
        Source.REPLAYS -> "replay"
        Source.SURVEILLANCE -> "sentry"
    }

    /**
     * Single UI-post path used by both the API-backed and FS-fallback
     * branches of [refreshCounts]. Mirrors the original pendingPosts /
     * stale-place-cleanup contract so cancellation in onDestroyView keeps
     * working unchanged.
     */
    private fun postCountsToUi(
        viewRef: WeakReference<View?>,
        dashcamCount: Long,
        replaysCount: Long,
        surveillanceCount: Long,
        totalCount: Long,
        totalToday: Long,
        totalBytes: Long,
        sortedPlaces: List<String>,
        warming: Boolean,
        /**
         * Recordings index is down: every count passed in is zero and NOT
         * authoritative, so the header must say so instead of rendering
         * "0 today · 0 total · 0 B" as fact.
         */
        indexDown: Boolean = false
    ) {
        val post = object : Runnable {
            override fun run() {
                synchronized(pendingPosts) { pendingPosts.remove(this) }
                val v = viewRef.get() ?: return
                val activeCtx = v.context ?: return
                skeleton?.markLoaded(R.id.tvRecordingsSummarySkeleton)
                val sizeText = Formatter.formatShortFileSize(activeCtx, totalBytes)

                val baseSummary = activeCtx.getString(
                    R.string.recordings_summary_format,
                    totalToday.toInt(),
                    totalCount.toInt(),
                    sizeText
                )
                v.findViewById<TextView>(R.id.tvRecordingsSummary)?.text = when {
                    indexDown -> activeCtx.getString(R.string.recordings_summary_index_down)
                    warming -> "$baseSummary  ·  (building index)"
                    else -> baseSummary
                }

                // While the index is down the per-segment counts are unknown,
                // so drop the "· N" suffix instead of asserting zero.
                v.findViewById<MaterialButton>(R.id.segmentDashcam)?.text =
                    if (indexDown) activeCtx.getString(R.string.recordings_segment_dashcam)
                    else activeCtx.getString(
                        R.string.recordings_segment_dashcam_count,
                        dashcamCount.toInt()
                    )
                v.findViewById<MaterialButton>(R.id.segmentReplays)?.text =
                    if (indexDown) activeCtx.getString(R.string.recordings_segment_replays)
                    else activeCtx.getString(
                        R.string.recordings_segment_replays_count,
                        replaysCount.toInt()
                    )
                v.findViewById<MaterialButton>(R.id.segmentSurveillance)?.text =
                    if (indexDown) activeCtx.getString(R.string.recordings_segment_surveillance)
                    else activeCtx.getString(
                        R.string.recordings_segment_surveillance_count,
                        surveillanceCount.toInt()
                    )
                v.findViewById<MaterialButton>(R.id.segmentAll)?.text =
                    if (indexDown) activeCtx.getString(R.string.recordings_segment_all)
                    else activeCtx.getString(
                        R.string.recordings_segment_all_count,
                        totalCount.toInt()
                    )

                availablePlaces = sortedPlaces
                // Drop any selected places that are no longer in the
                // available set (e.g. all clips for that place got
                // deleted). Otherwise the user sees an empty list with
                // an invisible filter blocking everything. Recomputed on
                // the main thread so a chip click between dispatch and
                // post-arrival is honoured.
                val availableLower = availablePlaces.map { it.lowercase() }.toSet()
                val staleSelections = placeFilter.filter { it !in availableLower }.toList()
                if (staleSelections.isNotEmpty()) {
                    placeFilter.removeAll(staleSelections.toSet())
                    libraryFragment?.let { applyAllFiltersTo(it) }
                    renderActiveFilterAffordances(v)
                }
                renderPlaceChips(v)

                // Schedule a follow-up poll while the index is rebuilding
                // so the header lights up as soon as warming flips false.
                // Single in-flight retry — we cancel the prior one so a
                // burst of refreshCounts() calls (segment switch + onResume)
                // doesn't fan out N parallel polls. Exponential backoff
                // (2 s → 4 s → 8 s → 10 s cap) so a 60+ s warmup doesn't
                // pile 30 redundant requests on the daemon.
                warmingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
                warmingRetryRunnable = null
                // Poll while the index is REBUILDING or DOWN. Without the
                // indexDown case the header would latch on "Counts
                // unavailable" until the next onResume / user action, even
                // after the daemon re-opened its index seconds later.
                if (warming || indexDown) {
                    val attempt = warmingRetryAttempt
                            .coerceAtMost(WARMING_POLL_MAX_ATTEMPTS_FOR_BACKOFF)
                    val delay = (WARMING_POLL_INTERVAL_MS shl attempt)
                            .coerceAtMost(WARMING_POLL_CAP_MS)
                    warmingRetryAttempt++
                    val retry = Runnable {
                        warmingRetryRunnable = null
                        refreshCounts()
                    }
                    warmingRetryRunnable = retry
                    mainHandler.postDelayed(retry, delay)
                } else {
                    // Index ready — reset attempt counter so a future
                    // warmup window starts at 2 s, not at the cap.
                    warmingRetryAttempt = 0
                }
            }
        }
        synchronized(pendingPosts) { pendingPosts.add(post) }
        mainHandler.post(post)
    }

    /**
     * FS-fallback chip-set derivation — only used when the daemon is
     * unreachable. Mirrors the legacy in-process bucket logic.
     */
    private fun derivePlaceLabelsFromScan(segmentClips: List<RecordingFile>): List<String> {
        data class PlaceBucket(var count: Int, var displayLabel: String, var newestTs: Long)
        val placeBuckets = LinkedHashMap<String, PlaceBucket>()
        for (rec in segmentClips) {
            val raw = rec.placeShortLabel ?: continue
            if (raw.isEmpty()) continue
            val key = raw.lowercase()
            val bucket = placeBuckets[key]
            if (bucket == null) {
                placeBuckets[key] = PlaceBucket(1, raw, rec.timestamp)
            } else {
                bucket.count++
                if (rec.timestamp > bucket.newestTs) {
                    bucket.newestTs = rec.timestamp
                    bucket.displayLabel = raw
                }
            }
        }
        return placeBuckets.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, PlaceBucket>> { it.value.count }
                    .thenBy { it.key }
            )
            .take(MAX_PLACE_CHIPS)
            .map { it.value.displayLabel }
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
     * (Re)build the Place chip row from [availablePlaces]. Hidden when no
     * geocoded clips exist — legacy users never see the row appear unless
     * they enable the feature and capture at least one tagged clip.
     *
     * Chip identity is the lowercased label (matched against
     * [placeFilter] / [RecordingFile.placeShortLabel]); the chip TEXT is
     * the canonical mixed-case form so "Cheras" doesn't render as
     * "cheras" just because the lowercase form drives matching.
     *
     * Includes a leading "Any" chip whose tap clears [placeFilter].
     */
    private fun renderPlaceChips(view: View) {
        val row = view.findViewById<View>(R.id.rowPlaceFilter) ?: return
        val group = view.findViewById<ChipGroup>(R.id.chipGroupPlace) ?: return
        if (availablePlaces.isEmpty()) {
            row.visibility = View.GONE
            group.removeAllViews()
            return
        }
        row.visibility = View.VISIBLE
        group.removeAllViews()

        val ctx = view.context
        // Cap individual chip width so a SafeLocation label like
        // "Wonderfully Quaint Borough of South-Eastern Münster" can't
        // push every later chip out of the horizontally-scrolled row's
        // visible area or distort layout. ~180dp matches the wider
        // existing chip presets in the toolbar.
        val maxChipWidthPx = (180 * ctx.resources.displayMetrics.density).toInt()

        // "Any" — clears row.
        val anyChip = Chip(ctx)
        anyChip.applyAppCornerShape()
        anyChip.text = ctx.getString(R.string.recording_lib_chip_any)
        anyChip.isCheckable = true
        anyChip.isChecked = placeFilter.isEmpty()
        anyChip.isSingleLine = true
        anyChip.ellipsize = android.text.TextUtils.TruncateAt.END
        anyChip.maxWidth = maxChipWidthPx
        // Any is the only chip checked when nothing's selected; tapping it
        // again should NOT toggle off (otherwise the row enters an
        // ambiguous "everything is unchecked but no filter is active"
        // state — same UX as the existing chipActorAny / chipSevAny).
        anyChip.setOnClickListener {
            if (placeFilter.isNotEmpty()) {
                placeFilter.clear()
                onPlaceFilterChanged(view)
            } else {
                anyChip.isChecked = true  // re-arm — Any is sticky once selected
            }
        }
        group.addView(anyChip)

        for (label in availablePlaces) {
            val key = label.lowercase()
            val chip = Chip(ctx)
            chip.applyAppCornerShape()
            chip.text = label
            chip.isCheckable = true
            chip.isChecked = key in placeFilter
            chip.isSingleLine = true
            chip.ellipsize = android.text.TextUtils.TruncateAt.END
            chip.maxWidth = maxChipWidthPx
            chip.setOnClickListener {
                if (key in placeFilter) {
                    placeFilter.remove(key)
                } else {
                    placeFilter.clear()
                    placeFilter.add(key)
                }
                onPlaceFilterChanged(view)
            }
            group.addView(chip)
        }
    }

    /**
     * Atomic re-render after a Place chip toggle: re-syncs the Any chip
     * checked state, refreshes the row's reset-button affordance, and
     * pushes the updated filter into the library.
     */
    private fun onPlaceFilterChanged(view: View) {
        // Re-sync the "Any" chip across the whole row. Easiest: full
        // re-render — chip-count is bounded by MAX_PLACE_CHIPS so this is
        // cheap. Avoids a partial-update bug where Any stays "checked"
        // alongside a data chip.
        renderPlaceChips(view)
        renderActiveFilterAffordances(view)
        libraryFragment?.let { applyAllFiltersTo(it) }
    }

    /**
     * Aggregate stats across an already-scanned list of [RecordingFile]s:
     * total clip count, count of clips modified today, and summed byte size.
     */
    private fun aggregate(list: List<RecordingFile>, startOfTodayMillis: Long): DirStats {
        var total = 0
        var today = 0
        var bytes = 0L
        for (rec in list) {
            total++
            bytes += rec.sizeBytes
            if (rec.timestamp >= startOfTodayMillis) today++
        }
        return DirStats(total, today, bytes)
    }

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    // -----------------------------------------------------------------
    // List | Timeline
    // -----------------------------------------------------------------

    /** Wires List | Timeline, the date pill and Live; the mode is resolved once per instance. */
    private fun setupTimelineMode(root: View, savedInstanceState: Bundle?) {
        val ctx = context ?: return
        if (!timelineModeResolved) {
            timelineModeResolved = true
            val args = if (savedInstanceState == null) arguments else null
            val saved = savedInstanceState
                ?.takeIf { it.containsKey(KEY_TIMELINE_MODE) }
                ?.getBoolean(KEY_TIMELINE_MODE)
            timelineMode = TimelinePrefs.resolveInitialMode(
                saved = saved,
                argView = args?.getString(TimelineLinks.ARG_VIEW),
                argParking = !args?.getString("parkingSessionId").isNullOrBlank(),
                remembered = TimelinePrefs.isTimelineRemembered(ctx),
            )
            if (timelineMode && args != null) {
                timelineStartMs = args.getLong(TimelineLinks.ARG_TIME_MS, -1L)
                timelineEndMs = args.getLong(TimelineLinks.ARG_END_MS, -1L)
            }
        }
        val toggle = root.findViewById<MaterialButtonToggleGroup>(R.id.recViewToggle) ?: return
        suppressViewToggle = true
        toggle.check(if (timelineMode) R.id.btnViewTimeline else R.id.btnViewList)
        suppressViewToggle = false
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressViewToggle) return@addOnButtonCheckedListener
            val wantTimeline = checkedId == R.id.btnViewTimeline
            if (wantTimeline == timelineMode) return@addOnButtonCheckedListener
            context?.let { TimelinePrefs.setTimelineRemembered(it, wantTimeline) }
            if (wantTimeline) {
                // A clip playing in the List carries on in the timeline from the same moment.
                val at = inlinePlayingWallMs()
                if (at > 0L) enterTimelineMode(root, at, true) else enterTimelineMode(root, -1L, false)
            } else {
                exitTimelineMode(root)
            }
        }
        root.findViewById<View>(R.id.btnTimelineDate)?.setOnClickListener { showTimelineDatePicker() }
        root.findViewById<MaterialButton>(R.id.btnTimelineLive)?.let { live ->
            live.iconTint = null
            live.setOnClickListener {
                runCatching {
                    findNavController().navigate(
                        R.id.liveViewFragment, null, com.overdrive.app.ui.util.NavOptionsExt.m3FadeThrough()
                    )
                }
            }
        }
        root.findViewById<View>(R.id.btnPreviewShowInTimeline)?.setOnClickListener {
            val recording = selectedInlineRecording ?: return@setOnClickListener
            val at = TimelineLinks.timeFromFilename(recording.file.name) ?: recording.timestamp
            // Only the List/Timeline toggle sets the default view; this is a one-off jump.
            suppressViewToggle = true
            toggle.check(R.id.btnViewTimeline)
            suppressViewToggle = false
            enterTimelineMode(root, at, true)
        }
        updateTimelineDatePill(root, if (timelineDayMs > 0L) timelineDayMs else System.currentTimeMillis())
        if (!timelineMode) return
        applyTimelineChrome(root, true)
        val existing = timelineFragment()
        if (existing != null) {
            bindTimelineFragment(existing)
            if (existing.isHidden) childFragmentManager.commit { show(existing) }
        } else {
            val tl = TimelineFragment.newInstance(timelineStartMs, timelineEndMs)
            bindTimelineFragment(tl)
            childFragmentManager.commit { replace(R.id.timelineContainer, tl, TAG_TIMELINE) }
        }
        if (timelineStartMs > 0L) updateTimelineDatePill(root, timelineStartMs)
        timelineStartMs = -1L
        timelineEndMs = -1L
    }

    private fun timelineFragment(): TimelineFragment? =
        childFragmentManager.findFragmentByTag(TAG_TIMELINE) as? TimelineFragment

    private fun bindTimelineFragment(tl: TimelineFragment) {
        tl.onDayChanged = { day ->
            timelineDayMs = day
            view?.let { updateTimelineDatePill(it, day) }
        }
    }

    /** Stops inline playback first so nothing keeps playing behind the timeline. */
    private fun enterTimelineMode(root: View, atMs: Long, play: Boolean) {
        setPlayerFullscreen(false)
        removeInlinePlayerForTimeline()
        if (root.findViewById<View>(R.id.recSelectionBar)?.visibility == View.VISIBLE ||
            libraryFragment?.isSelectingFromHost() == true
        ) {
            runCatching { libraryFragment?.exitSelectionFromHost() }
        }
        timelineMode = true
        applyTimelineChrome(root, true)
        val manager = childFragmentManager
        runCatching { manager.executePendingTransactions() }
        val existing = timelineFragment()
        if (existing != null) {
            bindTimelineFragment(existing)
            // Kept hidden since the List: shown as it was (position, zoom, filter), or moved to [atMs].
            if (existing.isHidden) {
                runCatching { manager.beginTransaction().show(existing).commitNowAllowingStateLoss() }
                    .onFailure { runCatching { manager.beginTransaction().show(existing).commitAllowingStateLoss() } }
            }
            if (atMs > 0L) {
                existing.jumpTo(atMs, play)
            } else {
                existing.currentWallMs.takeIf { it > 0L }?.let { updateTimelineDatePill(root, it) }
            }
        } else {
            val tl = TimelineFragment.newInstance(atMs, -1L)
            bindTimelineFragment(tl)
            runCatching {
                manager.beginTransaction()
                    .replace(R.id.timelineContainer, tl, TAG_TIMELINE)
                    .commitNowAllowingStateLoss()
            }.onFailure {
                runCatching {
                    manager.beginTransaction()
                        .replace(R.id.timelineContainer, tl, TAG_TIMELINE)
                        .commitAllowingStateLoss()
                }
            }
        }
        if (atMs > 0L) updateTimelineDatePill(root, atMs)
    }

    private fun exitTimelineMode(root: View) {
        timelineMode = false
        val manager = childFragmentManager
        runCatching { manager.executePendingTransactions() }
        timelineFragment()?.let { tl ->
            tl.onDayChanged = null
            // Hidden, not removed: it pauses and stops polling, and switching back is instant.
            if (!tl.isHidden) {
                runCatching { manager.beginTransaction().hide(tl).commitNowAllowingStateLoss() }
                    .onFailure { runCatching { manager.beginTransaction().hide(tl).commitAllowingStateLoss() } }
            }
        }
        applyTimelineChrome(root, false)
    }

    /** Wall time the inline player is at while it plays, else -1. */
    private fun inlinePlayingWallMs(): Long {
        val rec = selectedInlineRecording ?: return -1L
        val player = (childFragmentManager.findFragmentByTag(TAG_INLINE_PLAYER)
            ?: childFragmentManager.findFragmentById(R.id.previewContainer)) as? VideoPlayerFragment ?: return -1L
        val pos = player.playingPositionMs()
        if (pos < 0L) return -1L
        return (TimelineLinks.timeFromFilename(rec.file.name) ?: rec.timestamp) + pos
    }

    private fun removeInlinePlayerForTimeline() {
        val manager = childFragmentManager
        val player = (manager.findFragmentByTag(TAG_INLINE_PLAYER)
            ?: manager.findFragmentById(R.id.previewContainer)) as? VideoPlayerFragment
        activeInlinePath = null
        selectedInlineRecording = null
        currentInlineIndex = -1
        libraryFragment?.setActiveRecording(null)
        showPreviewPlaceholder()
        if (player == null) return
        player.onFullscreenToggle = null
        player.onPlaylistItemChanged = null
        player.onDurationResolved = null
        runCatching { manager.beginTransaction().remove(player).commitNowAllowingStateLoss() }
            .onFailure { runCatching { manager.beginTransaction().remove(player).commitAllowingStateLoss() } }
    }

    /** Hides every list-only view (remembering its visibility) for the timeline, or restores them. */
    private fun applyTimelineChrome(root: View, on: Boolean) {
        val container = root.findViewById<View>(R.id.timelineContainer) ?: return
        val column = container.parent as? ViewGroup ?: return
        val keep = listOfNotNull(
            root.findViewById<View>(R.id.btnRecordingsSettings),
            root.findViewById<View>(R.id.recViewToggle),
        )
        if (on) {
            for (i in 0 until column.childCount) {
                val child = column.getChildAt(i)
                if (child === container || keep.any { isAncestorOrSelf(child, it) }) continue
                hideForTimeline(child)
            }
            hideForTimeline(root.findViewById<View>(R.id.cardPlaceSearch))
            hideForTimeline(root.findViewById<View>(R.id.btnSelectRecordings))
            hideForTimeline(root.findViewById<View>(R.id.btnTogglePlaceSearch))
            container.visibility = View.VISIBLE
        } else {
            container.visibility = View.GONE
            for ((v, visibility) in timelineHiddenViews) v.visibility = visibility
            timelineHiddenViews.clear()
        }
        val timelineOnly = if (on) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.recTimelineHeaderSpace)?.visibility = timelineOnly
        root.findViewById<View>(R.id.btnTimelineDate)?.visibility = timelineOnly
        root.findViewById<View>(R.id.btnTimelineLive)?.visibility = timelineOnly
    }

    private fun hideForTimeline(v: View?) {
        if (v == null || timelineHiddenViews.containsKey(v)) return
        timelineHiddenViews[v] = v.visibility
        v.visibility = View.GONE
    }

    private fun isAncestorOrSelf(ancestor: View, view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    /** "Today · Sat 15 Nov" for the timeline's current day. */
    private fun updateTimelineDatePill(root: View, dayMs: Long) {
        val pill = root.findViewById<MaterialButton>(R.id.btnTimelineDate) ?: return
        val locale = Locale.getDefault()
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = dayMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = startOfTodayMillis()
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = today
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        val date = SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale
        ).format(Date(dayMs))
        val relative = when (dayStart) {
            today -> getString(R.string.timeline_today)
            yesterday -> getString(R.string.timeline_yesterday)
            else -> null
        }
        pill.text = if (relative != null) getString(R.string.timeline_gap_fmt, relative, date) else date
    }

    /** The day picker: days with footage (and alerts) marked; a pick opens that day, the strip picks the time. */
    private fun showTimelineDatePicker() {
        timelineFragment()?.takeIf { it.isAdded && !it.isHidden }?.showDayPicker()
    }

    private data class DirStats(val total: Int, val today: Int, val bytes: Long)

    companion object {
        private const val KEY_SOURCE = "recordings_source"
        private const val KEY_ACTORS = "recordings_actor_classes"
        private const val KEY_SEVERITY = "recordings_severity"
        private const val KEY_DASHCAM_TYPES = "recordings_dashcam_types"
        private const val KEY_PLACES = "recordings_places"
        private const val KEY_STORAGE = "recordings_storage"
        private const val KEY_DATE_Y = "recordings_date_year"
        private const val KEY_DATE_M = "recordings_date_month"
        private const val KEY_DATE_D = "recordings_date_day"
        private const val KEY_DATE_NARROWED = "recordings_date_narrowed"
        private const val KEY_PLAYER_FULLSCREEN = "recordings_player_fullscreen"
        private const val KEY_PLACE_CONTAINS = "recordings_place_contains"
        private const val KEY_PARKING_SESSION = "recordings_parking_session"
        private const val TAG_INLINE_PLAYER = "inline_player"
        private const val KEY_TIMELINE_MODE = "recordings_timeline_mode"
        private const val TAG_TIMELINE = "timeline_screen"
        /** Cap on chips to avoid sprawl after a long road trip. */
        private const val MAX_PLACE_CHIPS = 8
        /** Debounce window for the landscape free-text place-search field. */
        private const val PLACE_SEARCH_DEBOUNCE_MS = 300L
        /**
         * Initial delay (and minimum) for warming-state stats poll.
         * Doubles per retry up to {@link #WARMING_POLL_CAP_MS}.
         */
        private const val WARMING_POLL_INTERVAL_MS: Long = 2_000L
        /** Hard cap so we never sleep more than 10 s between probes. */
        private const val WARMING_POLL_CAP_MS: Long = 10_000L
        /** Max shift exponent for backoff doubling — saturates the cap. */
        private const val WARMING_POLL_MAX_ATTEMPTS_FOR_BACKOFF = 8

        /** Library column weight in the two-pane body (preview card is 11). */
        private const val LIBRARY_PANE_WEIGHT = 10f
        private const val TYPE_BADGE_FILL_ALPHA = 0x24
        private const val DISABLED_ACTION_ALPHA = 0.38f

        // Surface roles resolve against material's attr namespace here, not the
        // app's (nonTransitiveRClass).
        private val SURFACE_HIGHEST_ATTR =
            com.google.android.material.R.attr.colorSurfaceContainerHighest
        private val ON_SURFACE_VARIANT_ATTR =
            com.google.android.material.R.attr.colorOnSurfaceVariant
        private val PRIMARY_ATTR = androidx.appcompat.R.attr.colorPrimary
        private val ON_PRIMARY_ATTR = com.google.android.material.R.attr.colorOnPrimary
        private val ON_PRIMARY_CONTAINER_ATTR =
            com.google.android.material.R.attr.colorOnPrimaryContainer
    }
}

private fun View.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr)

/** Tints a shaped badge background so its corners survive; falls back to a flat fill. */
private fun TextView.paintBadge(@ColorInt fill: Int, @ColorInt label: Int) {
    if (background != null) {
        backgroundTintList = ColorStateList.valueOf(fill)
    } else {
        setBackgroundColor(fill)
    }
    setTextColor(label)
}

/** Filled count pill drawn in place of its digits (the Filters button's active-filter count). */
private class CountBadgeSpan(
    @ColorInt private val fill: Int,
    @ColorInt private val textColor: Int,
    private val textSizePx: Float,
    private val sizePx: Float
) : ReplacementSpan() {

    private val rect = RectF()

    private fun badgePaint(base: Paint) = TextPaint(base).apply {
        textSize = textSizePx
        fontFeatureSettings = "tnum"
        isAntiAlias = true
    }

    private fun badgeWidth(paint: TextPaint, text: CharSequence, start: Int, end: Int): Float =
        maxOf(sizePx, paint.measureText(text, start, end) + sizePx * 0.5f)

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = kotlin.math.ceil(badgeWidth(badgePaint(paint), text, start, end)).toInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val p = badgePaint(paint)
        val width = badgeWidth(p, text, start, end)
        // Centre on the label's glyph box so the pill sits level with the text.
        val centerY = y + (paint.ascent() + paint.descent()) / 2f
        rect.set(x, centerY - sizePx / 2f, x + width, centerY + sizePx / 2f)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        canvas.drawRoundRect(rect, sizePx / 2f, sizePx / 2f, fillPaint)
        p.color = textColor
        val textWidth = p.measureText(text, start, end)
        val baseline = centerY - (p.ascent() + p.descent()) / 2f
        canvas.drawText(text, start, end, x + (width - textWidth) / 2f, baseline, p)
    }
}
