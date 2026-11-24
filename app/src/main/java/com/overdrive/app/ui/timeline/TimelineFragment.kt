package com.overdrive.app.ui.timeline

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.overdrive.app.R
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.timeline.player.TimelinePlayer
import com.overdrive.app.util.DaemonHttpClient
import com.overdrive.app.ui.timeline.player.VideoPipeline
import com.overdrive.app.ui.timeline.view.CameraTile
import com.overdrive.app.ui.timeline.view.TimelineVideoView
import com.overdrive.app.ui.util.NavOptionsExt
import com.overdrive.app.ui.view.PlaybackDewarp
import com.overdrive.app.ui.view.ZoomableVideoView
import com.overdrive.app.ui.widget.AppToast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.TimeZone
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Recordings Timeline: a continuous player over every clip, the time strip
 * under a fixed centre playhead, and the day's Moments. Hosted by
 * RecordingsFragment, which owns the header (date pill, Live, List|Timeline).
 */
class TimelineFragment : Fragment() {

    /** Host hook: the playhead moved into another local day (day start, epoch ms). */
    var onDayChanged: ((Long) -> Unit)? = null

    val currentWallMs: Long
        get() = if (positionMs > 0L) positionMs else retainedWallMs

    // Survive view recreation (back stack); persisted in onSaveInstanceState.
    private var retainedWallMs = UNSET
    private var retainedPlaying = false
    private var speed = 1
    private var skipQuiet = false
    private var continueNext = true
    private var preferSource: String? = null
    private var filterKey = MomentText.FILTER_ALL
    private var spanMs = DEFAULT_SPAN_MS
    private var camera = ZoomableVideoView.Quadrant.ALL
    private var argsConsumed = false
    private var pendingJumpMs = UNSET
    private var pendingJumpPlay = false
    private var dewarpStrength = -1

    private var repo: TimelineRepository? = null
    private var specs: ClipSpecLoader? = null
    private var io: ExecutorService? = null

    // View-scoped.
    private var ui: Ui? = null
    private var player: TimelinePlayer? = null
    private var videoView: TimelineVideoView? = null
    private var strip: TimelineStripView? = null
    private var adapter: MomentsAdapter? = null
    private var speedMenu: SpeedMenu? = null
    private var appToast: AppToast? = null
    private var viewGen = 0
    private var playerState: TimelinePlayer.State = TimelinePlayer.State.IDLE
    private var playing = false
    private var positionMs = UNSET
    private var currentSeg: TlSegment? = null
    private var theaterMode = false
    private var theaterParentGravity: Int? = null
    private var shareExec: ExecutorService? = null
    @Volatile private var shareInFlight = false
    private val theaterBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setTheater(false)
        }
    }
    private var families: List<String> = emptyList()
    private var activeFamily: String? = null
    private var scrubbing = false
    private var scrubWasPlaying = false
    private var autoPositionPending = false
    private var lastUserScrollAt = 0L
    private var lastDayReported = UNSET
    private var lastErrorToastAt = 0L
    private var reducedMotion = false
    private var gapTargetMs = UNSET
    // The explicit seek the gap card describes (UNSET when hidden), and whether it sits on a clip still recording.
    private var seekGapAt = UNSET
    private var flashedGapText: CharSequence? = null
    private var seekGapNext = UNSET
    private var seekGapLive = false
    // Waiting for a clip to be saved (Play on a held live moment, or playback ran out at the newest clip while
    // recording): the player starts it by itself; the button shows Pause and cancels the wait.
    private var liveFollow = false
        set(v) {
            field = v
            updatePlayIcon(playerState == TimelinePlayer.State.PLAYING)
        }
    // The unanalysed or damaged clip whose sliver the card names: once analysed, the playhead lands on it.
    private var seekGapSliver: String? = null
    // The clip that just failed: the player follows past it, so it isn't footage still to play.
    private var failedSegId: String? = null
    // Playback (not a seek) ran into the last ENDED/ERROR: an error that follows may still start a wait.
    private var endedWhilePlaying = false
    private var suppressGroupEvents = false
    private var notInClipHintFor: String? = null
    // The object chip's lift above the pill (NaN until placed).
    private var chipLift = Float.NaN
    // The renderer's decoder surface, handed back to the player when the hidden timeline shows again.
    private var decoderSurface: Surface? = null
    private var detachedWhileHidden = false
    // A picked day not loaded yet: its best moment is aimed at once its data arrives.
    private var pendingDayMs = UNSET
    private var dayPicker: TlDayPicker? = null
    private var reopenPickerMonth = UNSET
    // Position-path early outs: the chip, current moment and day are recomputed only when their inputs change.
    private var chipKnown = false
    private var chipMark: TlMark? = null
    private var chipRoad: TlRoad? = null
    private var momentKeyList: List<TlSegment>? = null
    private var momentKeySeg: String? = null
    private var momentFrom = Long.MAX_VALUE
    private var momentTo = Long.MIN_VALUE
    private var lastDayEnd = UNSET
    private var lastDayTz: TimeZone? = null
    // Skip quiet driving picks 1x or 8x itself: the strip extrapolates at the rate the reports show.
    private var lastPosWall = UNSET
    private var lastPosAt = 0L
    private var autoSpeed = 1

    // Derived from the latest snapshot.
    private var marks: List<TlMark> = emptyList()
    private var road: List<TlRoad> = emptyList()
    private var maxMarkSpan = 0L
    private var markKinds: Map<String, Set<String>> = emptyMap()
    private var moments: List<TlSegment> = emptyList()
    private var currentMomentId: String? = null
    private var timeFormat: SimpleDateFormat? = null

    private val main = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { if (speedMenu?.isShowing != true) setControlsVisible(false) }
    private val showBuffering = Runnable { ui?.buffering?.visibility = View.VISIBLE }
    private val showLabels = Runnable { ui?.labels?.animate()?.alpha(1f)?.setDuration(FADE_MS)?.start() }

    private class Ui(root: View) {
        val root: View = root.findViewById(R.id.tlRoot)
        val playerBox: TlPlayerBox = root.findViewById(R.id.tlPlayerBox)
        val labels: TlCameraLabelsView = root.findViewById(R.id.tlCameraLabels)
        val emptyDay: TextView = root.findViewById(R.id.tlEmptyDay)
        val buffering: View = root.findViewById(R.id.tlBuffering)
        val sourceChip: TextView = root.findViewById(R.id.tlSourceChip)
        val objectChip: TextView = root.findViewById(R.id.tlObjectChip)
        val gapBox: View = root.findViewById(R.id.tlGapBox)
        val gapHint: TextView = root.findViewById(R.id.tlGapHint)
        val gapCard: TextView = root.findViewById(R.id.tlGapCard)
        val controls: View = root.findViewById(R.id.tlControls)
        val prev: ImageButton = root.findViewById(R.id.tlPrev)
        val playPause: ImageButton = root.findViewById(R.id.tlPlayPause)
        val next: ImageButton = root.findViewById(R.id.tlNext)
        val speed: TextView = root.findViewById(R.id.tlSpeed)
        val fullscreen: ImageButton = root.findViewById(R.id.tlFullscreen)
        val share: ImageButton = root.findViewById(R.id.tlShare)
        val panel: View = root.findViewById(R.id.tlPanel)
        val momentsPane: View = root.findViewById(R.id.tlMomentsPane)
        val momentsCount: TextView = root.findViewById(R.id.tlMomentsCount)
        val moments: RecyclerView = root.findViewById(R.id.tlMoments)
        val skeleton: View = root.findViewById(R.id.tlMomentsSkeleton)
        val momentsEmpty: TextView = root.findViewById(R.id.tlMomentsEmpty)
        val banner: View = root.findViewById(R.id.tlOfflineBanner)
        val retry: View = root.findViewById(R.id.tlRetry)
        val filterGroup: MaterialButtonToggleGroup = root.findViewById(R.id.tlFilterGroup)
        val zoomGroup: MaterialButtonToggleGroup = root.findViewById(R.id.tlZoomGroup)
        val stripHost: FrameLayout = root.findViewById(R.id.tlStripHost)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = requireContext()
        repo = TimelineRepository()
        specs = ClipSpecLoader()
        io = Executors.newSingleThreadExecutor { r -> Thread(r, "TimelineIo").apply { isDaemon = true } }
        skipQuiet = TimelinePrefs.skipQuiet(ctx)
        continueNext = TimelinePrefs.continueNext(ctx)
        preferSource = TimelinePrefs.preferredSource(ctx)
        savedInstanceState?.let { s ->
            retainedWallMs = s.getLong(KEY_WALL, UNSET)
            retainedPlaying = s.getBoolean(KEY_PLAYING, false)
            speed = s.getInt(KEY_SPEED, 1).takeIf { it in SPEEDS } ?: 1
            filterKey = s.getString(KEY_FILTER, MomentText.FILTER_ALL) ?: MomentText.FILTER_ALL
            spanMs = s.getLong(KEY_SPAN, DEFAULT_SPAN_MS).coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
            camera = s.getString(KEY_CAMERA)
                ?.let { runCatching { ZoomableVideoView.Quadrant.valueOf(it) }.getOrNull() }
                ?: ZoomableVideoView.Quadrant.ALL
            argsConsumed = s.getBoolean(KEY_ARGS_CONSUMED, false)
            reopenPickerMonth = s.getLong(KEY_DAY_PICKER, UNSET)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_timeline, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, theaterBack)
        viewGen++
        val u = Ui(view)
        ui = u
        appToast = AppToast(view)
        reducedMotion = isReducedMotion(ctx)
        playing = false
        playerState = TimelinePlayer.State.IDLE
        currentSeg = null
        lastDayReported = UNSET
        lastDayEnd = UNSET
        chipKnown = false
        momentKeyList = null
        lastPosWall = UNSET
        detachedWhileHidden = false
        timeFormat = newTimeFormat(ctx, repo?.timeZone ?: TimeZone.getDefault())

        val p = createPlayer(ctx)
        player = p
        val vv = TimelineVideoView(ctx)
        videoView = vv
        // The view delivers these on the main thread.
        vv.onDecoderSurface = { surface ->
            decoderSurface = surface
            if (!isHidden) player?.setOutput(surface)
        }
        vv.onDecoderSurfaceLost = {
            decoderSurface = null
            player?.setOutput(null)
        }
        vv.onCameraTapped = { q -> onCameraTapped(q) }
        vv.onTilesChanged = { tiles -> ui?.labels?.setTiles(tiles) }
        p.setFrameSink(vv)
        u.playerBox.addView(vv, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        vv.setCamera(camera, false)
        if (dewarpStrength >= 0) vv.setDewarpStrength(dewarpStrength)

        val st = TimelineStripView(ctx)
        strip = st
        u.stripHost.addView(st, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        st.setReducedMotion(reducedMotion)
        attachStripListener(st, stripListener)

        bindControls(u)
        bindMoments(u, ctx)
        bindFilters(u)
        bindPlayerBox(u)
        u.labels.setNames(cameraNames(ctx))
        speedMenu = SpeedMenu(ctx, { reducedMotion }, { scheduleControlsHide() }) { s -> applySpeedState(s) }

        p.setSpecProvider { seg -> specs?.specFor(seg) }
        p.setSpeed(speed)
        p.setSkipQuietDriving(skipQuiet)
        p.setContinueAcrossGaps(continueNext)
        p.setPreferredSource(preferSource)
        updateSpeedButton()
        loadMutePreference()

        val start = resolveStart(ctx)
        positionMs = start.first
        st.setSpan(spanMs, false)
        syncZoomChecks(spanMs)
        applyFilterToStrip()
        repo?.includeTime(start.first)
        pushStripData(repo?.currentSnapshot)
        p.seekTo(start.first, true)
        st.setPlayback(start.first, false, speed)
        if (start.second) p.play()
        repo?.ensureLoaded(start.first - spanMs / 2, start.first + spanMs / 2)
        repo?.setListener(repoListener)
        reportDay(start.first)
        setControlsVisible(true)
        // The day picker was open across a recreate: open it again on the same month.
        val month = reopenPickerMonth
        reopenPickerMonth = UNSET
        if (month != UNSET && !isHidden) view.post { if (ui === u) showDayPicker(month) }
    }

    override fun onResume() {
        super.onResume()
        // Hidden behind the List: it stays still until shown.
        if (!isHidden) becomeVisible()
    }

    override fun onPause() {
        leave(activity?.isChangingConfigurations == true)
        super.onPause()
    }

    /** List | Timeline keeps this fragment: hidden it pauses and stops polling; shown it resumes as it was. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            leave(false)
            speedMenu?.dismiss()
            dayPicker?.dismiss()
            main.removeCallbacks(hideControls)
            // The decoder goes after the player's surface grace (the List may need it); a held card keeps it,
            // so showing again doesn't re-run that seek as a playback gap.
            if (player != null && seekGapAt == UNSET) {
                detachedWhileHidden = true
                player?.setOutput(null)
            }
        } else {
            if (detachedWhileHidden) {
                detachedWhileHidden = false
                decoderSurface?.let { player?.setOutput(it) }
            }
            if (isResumed) becomeVisible()
            setControlsVisible(true)
        }
    }

    private fun becomeVisible() {
        val ctx = context ?: return
        val reduced = isReducedMotion(ctx)
        if (reduced != reducedMotion) {
            reducedMotion = reduced
            strip?.setReducedMotion(reduced)
        }
        repo?.setActive(true)
        refreshDewarpStrength()
    }

    // Leaving (paused, or hidden for the List): playback pauses and a pending "plays once saved" is cancelled.
    private fun leave(changingConfigurations: Boolean) {
        // Rotation keeps playing across the recreate; anything else stays paused on return.
        val wasPlaying = if (scrubbing) scrubWasPlaying else playing
        retainedPlaying = wasPlaying && changingConfigurations
        if (positionMs > 0L) retainedWallMs = positionMs
        liveFollow = false
        player?.pause()
        repo?.setActive(false)
        context?.let { TimelinePrefs.saveLastPosition(it, currentWallMs, System.currentTimeMillis()) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_WALL, currentWallMs)
        outState.putBoolean(KEY_PLAYING, retainedPlaying)
        outState.putInt(KEY_SPEED, speed)
        outState.putString(KEY_FILTER, filterKey)
        outState.putLong(KEY_SPAN, spanMs)
        outState.putString(KEY_CAMERA, camera.name)
        outState.putBoolean(KEY_ARGS_CONSUMED, argsConsumed)
        outState.putLong(KEY_DAY_PICKER, dayPicker?.takeIf { it.isShowing }?.month ?: UNSET)
    }

    override fun onDestroyView() {
        viewGen++
        speedMenu?.dismiss()
        speedMenu = null
        dayPicker?.dismiss()
        dayPicker = null
        decoderSurface = null
        main.removeCallbacks(hideControls)
        main.removeCallbacks(showBuffering)
        main.removeCallbacks(showLabels)
        appToast?.cancel()
        appToast = null
        repo?.setListener(null)
        if (positionMs > 0L) retainedWallMs = positionMs
        strip?.let { attachStripListener(it, null) }
        strip = null
        player?.let { p ->
            runCatching { p.setFrameSink(null) }
            runCatching { p.release() }
        }
        player = null
        videoView?.let { v ->
            v.onDecoderSurface = null
            v.onDecoderSurfaceLost = null
            v.onCameraTapped = null
            v.onTilesChanged = null
            runCatching { v.release() }
        }
        videoView = null
        ui?.moments?.adapter = null
        adapter?.dispose()
        adapter = null
        ui = null
        positionMs = UNSET
        // The card and its player are gone: a rebuilt view starts without a held moment.
        seekGapAt = UNSET
        seekGapNext = UNSET
        seekGapLive = false
        liveFollow = false
        seekGapSliver = null
        gapTargetMs = UNSET
        pendingDayMs = UNSET
        setTheater(false)
        super.onDestroyView()
    }

    override fun onDestroy() {
        repo?.release()
        repo = null
        specs?.release()
        specs = null
        io?.shutdownNow()
        io = null
        shareExec?.shutdownNow()
        shareExec = null
        onDayChanged = null
        super.onDestroy()
    }

    /** Host entry: open the timeline at [wallMs] (date pill, deep link, Show in timeline). */
    fun jumpTo(wallMs: Long, play: Boolean) {
        if (wallMs <= 0L) return
        if (ui == null || player == null) {
            pendingJumpMs = wallMs
            pendingJumpPlay = play
            return
        }
        jump(wallMs, play)
    }

    /** Host entry (date pill): a month of days, those with footage enabled; a pick opens the day's best moment. */
    fun showDayPicker() = showDayPicker(UNSET)

    private fun showDayPicker(month: Long) {
        val ctx = context ?: return
        val r = repo ?: return
        if (ui == null || dayPicker?.isShowing == true) return
        val data = dayPickerData() ?: return
        val picker = TlDayPicker(ctx, r.timeZone) { day -> jumpToDay(day) }
        dayPicker = picker
        picker.show(data, month)
        // The list is re-read only every few minutes: fresh marks for today while the grid is open.
        r.refreshDays()
    }

    private fun refreshDayPicker() {
        val p = dayPicker ?: return
        if (!p.isShowing) return
        // The recorder's zone arrived after it opened: its days are keyed differently now, so rebuild it.
        if (p.tz.id != repo?.timeZone?.id) {
            val month = p.month
            p.dismiss()
            dayPicker = null
            showDayPicker(month)
            return
        }
        dayPickerData()?.let { p.update(it) }
    }

    private fun dayPickerData(): TlDayPickerData? {
        val r = repo ?: return null
        val now = System.currentTimeMillis()
        // Loaded days with playable clips: fresher than the day list for today, and there if it failed.
        val loaded = HashSet<Long>()
        r.currentSnapshot?.segments?.forEach { s ->
            val end = s.end
            if (s.available && end != null && end > s.start) {
                loaded.add(r.dayStartOf(s.start))
                loaded.add(r.dayStartOf(end - 1L))
            }
        }
        val at = currentWallMs.takeIf { it > 0L } ?: now
        return TlDayPickerData(
            stats = r.dayStats,
            footage = loaded,
            minDay = r.dayStartOf(minOf(r.minMs, now)),
            today = r.dayStartOf(now),
            selected = r.dayStartOf(at),
        )
    }

    // A picked day opens where the web's bestTargetInDay would; an unloaded day is aimed again once it loads.
    // Playback that was running carries on there.
    private fun jumpToDay(day: Long) {
        val r = repo ?: return
        r.includeTime(day)
        val snap = r.currentSnapshot
        jump(bestTargetInDay(day, snap), playing)
        if (snap == null || day !in snap.loadedDays) pendingDayMs = day
    }

    private fun maybeAimPendingDay(snap: TimelineRepository.Snapshot) {
        val day = pendingDayMs
        if (day == UNSET || day !in snap.loadedDays) return
        val t = bestTargetInDay(day, snap)
        pendingDayMs = UNSET
        // A wait for the day's clips keeps the player's own intent to play, whatever the button shows meanwhile.
        if (t != positionMs) jump(t, playing)
    }

    /** The snapshot's loaded days as merged [from, to) wall spans: a seek while playing elsewhere waits for its clips. */
    private fun loadedSpans(snap: TimelineRepository.Snapshot): LongArray {
        val r = repo ?: return LongArray(0)
        val out = ArrayList<Long>(snap.loadedDays.size * 2)
        for (d in snap.loadedDays.sorted()) {
            val e = r.nextDayStart(d)
            if (out.isNotEmpty() && out[out.size - 1] >= d) out[out.size - 1] = maxOf(out[out.size - 1], e)
            else {
                out.add(d)
                out.add(e)
            }
        }
        return out.toLongArray()
    }

    private fun bestTargetInDay(day: Long, snap: TimelineRepository.Snapshot?): Long {
        val r = repo ?: return day
        val next = r.nextDayStart(day)
        val segs = snap?.segments.orEmpty()
        val newest = segs.asSequence()
            .filter { MomentText.isMoment(it.type) && it.start >= day && it.start < next && passesFilter(it) }
            .maxOfOrNull { it.start }
        val latest = segs.filter { it.available && it.end != null && it.start < next && it.end > day }
            .maxByOrNull { it.end ?: 0L }
        return TimelineNav.bestInDay(listOfNotNull(newest), latest?.start, latest?.end, day, next, System.currentTimeMillis())
    }

    // ---------------------------------------------------------------
    // Player
    // ---------------------------------------------------------------

    private fun createPlayer(ctx: Context): TimelinePlayer {
        val gen = viewGen
        return TimelinePlayer(ctx, object : TimelinePlayer.Listener {
            override fun onPosition(wallMs: Long, playing: Boolean) =
                onUi(gen) { handlePosition(wallMs, playing) }

            override fun onState(state: TimelinePlayer.State) = onUi(gen) { handleState(state) }

            override fun onItemChanged(seg: TlSegment?, spec: ClipSpec?) =
                onUi(gen) { handleItem(seg, spec) }

            override fun onSources(families: List<String>, active: String?) =
                onUi(gen) { handleSources(families, active) }

            override fun onGap(fromWall: Long, toWall: Long) = onUi(gen) { handleGap(fromWall, toWall) }

            override fun onSeekGap(atWall: Long, nextWall: Long) = onUi(gen) { handleSeekGap(atWall, nextWall) }

            override fun onSeekGapSkipped(atWall: Long, nextWall: Long) = onUi(gen) {
                flashSkippedGap(atWall)
                strip?.glideTo(nextWall)
            }

            override fun onError(seg: TlSegment?, message: String) = onUi(gen) { handleError(seg, message) }

            override fun onVideoSize(w: Int, h: Int) = onUi(gen) { videoView?.setVideoSize(w, h) }
            // The wait expired: a clip landing later should show its frame, not wait on a follow that's gone.
            override fun onFollowDropped() = onUi(gen) { liveFollow = false }
        })
    }

    // Player callbacks may arrive on its threads; a stale view generation is dropped.
    private fun onUi(gen: Int, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (gen == viewGen && ui != null) block()
        } else {
            main.post { if (gen == viewGen && ui != null) block() }
        }
    }

    private fun handlePosition(wallMs: Long, isPlaying: Boolean) {
        positionMs = wallMs
        if (!scrubbing && playing != isPlaying) {
            playing = isPlaying
            refreshRowsIfCurrent()
        }
        if (!scrubbing) strip?.setPlayback(wallMs, isPlaying, stripSpeed(wallMs, isPlaying))
        if (gapTargetMs > 0L && wallMs >= gapTargetMs - GAP_SLACK_MS) hideGapHint()
        updateObjectChip(wallMs)
        updateCurrentMoment(wallMs)
        reportDay(wallMs)
    }

    // Skip quiet driving runs quiet drives at the skim speed and events at 1x: the strip follows the reported rate.
    private fun stripSpeed(wallMs: Long, isPlaying: Boolean): Int {
        if (!skipQuiet) return speed
        val now = SystemClock.uptimeMillis()
        val prevWall = lastPosWall
        val dt = now - lastPosAt
        lastPosAt = now
        lastPosWall = wallMs
        val dw = wallMs - prevWall
        if (isPlaying && prevWall != UNSET && dt in 20L..500L && dw >= 0L) {
            autoSpeed = if (dw > dt * 3) VideoPipeline.SKIM_SPEED.toInt() else 1
        }
        return autoSpeed
    }

    private fun updatePlayIcon(isPlaying: Boolean) {
        val u = ui ?: return
        val on = isPlaying || liveFollow
        u.playPause.setImageResource(if (on) R.drawable.rec_ic_pause else R.drawable.rec_ic_play)
        u.playPause.contentDescription = getString(if (on) R.string.timeline_cd_pause else R.string.timeline_cd_play)
    }

    private fun handleState(state: TimelinePlayer.State) {
        val prev = playerState
        playerState = state
        val u = ui ?: return
        val isPlaying = state == TimelinePlayer.State.PLAYING
        if (!scrubbing && playing != isPlaying) {
            playing = isPlaying
            refreshRowsIfCurrent()
        }
        // Ran out at the newest clip while one is recording: the player waits for it; say so (Pause cancels).
        val ranOut = (state == TimelinePlayer.State.ENDED || state == TimelinePlayer.State.ERROR) &&
            (prev == TimelinePlayer.State.PLAYING || prev == TimelinePlayer.State.BUFFERING)
        if (state != prev) endedWhilePlaying = ranOut
        if (ranOut && !scrubbing && seekGapAt == UNSET) {
            val wait = liveTailWait(positionMs)
            if (wait != UNSET) {
                handleSeekGap(wait, UNSET)
                liveFollow = true
            }
        }
        updatePlayIcon(isPlaying)
        main.removeCallbacks(showBuffering)
        if (state == TimelinePlayer.State.BUFFERING) {
            main.postDelayed(showBuffering, BUFFERING_DELAY_MS)
        } else {
            u.buffering.visibility = View.GONE
        }
        if (isPlaying) {
            // Playing under a held card (e.g. a follow started elsewhere): its cause is gone.
            if (seekGapAt != UNSET) hideGapHint()
            scheduleControlsHide()
        } else if (state != TimelinePlayer.State.SCRUBBING) {
            main.removeCallbacks(hideControls)
            setControlsVisible(true)
        }
    }

    private fun handleItem(seg: TlSegment?, spec: ClipSpec?) {
        currentSeg = seg
        if (seg != null) {
            val resolved = spec ?: specs?.cached(seg) ?: ClipSpecLoader.fallback(seg)
            // While decoding continuously the player switches specs on the exact frame.
            if (!frameScheduled()) videoView?.setClipSpecNow(resolved)
            if (resolved.single && camera != ZoomableVideoView.Quadrant.ALL && notInClipHintFor != seg.id) {
                notInClipHintFor = seg.id
                cameraName(camera)?.let { appToast?.show(getString(R.string.timeline_cam_not_in_clip, it)) }
            }
            prefetchSpecsAround(seg.start)
        }
        // Camera names stay hidden under the gap card: the 2x2 tiles' corners meet where it sits.
        ui?.let { u -> u.labels.visibility = if (seg != null && u.gapBox.visibility != View.VISIBLE) View.VISIBLE else View.INVISIBLE }
        updateCurrentMoment(positionMs)
    }

    private fun frameScheduled(): Boolean =
        playerState == TimelinePlayer.State.PLAYING || playerState == TimelinePlayer.State.BUFFERING

    private fun handleSources(list: List<String>, active: String?) {
        families = list
        activeFamily = active
        val chip = ui?.sourceChip ?: return
        if (list.size < 2) {
            chip.visibility = View.GONE
            return
        }
        val label = getString(if (isOemFamily(active)) R.string.timeline_source_oem else R.string.timeline_source_mosaic)
        chip.text = label
        chip.contentDescription = getString(R.string.timeline_gap_fmt, getString(R.string.timeline_source_switch), label)
        if (chip.visibility != View.VISIBLE) {
            chip.alpha = 0f
            chip.visibility = View.VISIBLE
            chip.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    /** A user seek into unrecorded time: the playhead stays, the card says why, Play continues from [nextWall]. */
    private fun handleSeekGap(atWall: Long, nextWall: Long) {
        val u = ui ?: return
        val d = cardData()
        val m = d?.let { runCatching { StripModel.build(it) }.getOrNull() }
        seekGapAt = atWall
        seekGapNext = nextWall
        seekGapLive = m != null && m.liveSince != Long.MIN_VALUE && atWall >= m.liveSince
        // A new held moment: a sliver named for an earlier one doesn't carry over.
        seekGapSliver = null
        val text = if (d != null && m != null) seekGapText(d, m, atWall) else getString(R.string.timeline_gap_no_footage)
        u.gapCard.text = text
        u.gapCard.visibility = View.VISIBLE
        u.labels.visibility = View.INVISIBLE
        videoView?.alpha = GAP_VIDEO_ALPHA
        if (nextWall > atWall) {
            gapTargetMs = nextWall
            u.gapHint.text = getString(R.string.timeline_next_event_in, durationText(nextWall - atWall))
            u.gapHint.visibility = View.VISIBLE
        } else {
            gapTargetMs = UNSET
            u.gapHint.visibility = View.GONE
        }
        syncGapBox(u)
        setControlsVisible(true)
    }

    /** A move while playing skipped unrecorded time: say why for a moment while the next footage plays. */
    private fun flashSkippedGap(atWall: Long) {
        val u = ui ?: return
        hideGapHint()
        val d = cardData()
        val m = d?.let { runCatching { StripModel.build(it) }.getOrNull() }
        val text = if (d != null && m != null) seekGapText(d, m, atWall) else getString(R.string.timeline_gap_no_footage)
        flashedGapText = text
        u.gapCard.text = text
        u.gapCard.visibility = View.VISIBLE
        syncGapBox(u)
        main.removeCallbacks(hideFlashedGap)
        main.postDelayed(hideFlashedGap, GAP_FLASH_MS)
    }

    // Only the flashed text: a card raised since then stays.
    private val hideFlashedGap = Runnable {
        val u = ui ?: return@Runnable
        val t = flashedGapText ?: return@Runnable
        flashedGapText = null
        if (seekGapAt == UNSET && u.gapCard.visibility == View.VISIBLE && u.gapCard.text?.toString() == t.toString()) {
            u.gapCard.visibility = View.GONE
            syncGapBox(u)
        }
    }

    // One card on the video: the reason, then "Next event in …" as its tappable second line.
    private fun syncGapBox(u: Ui) {
        val show = u.gapCard.visibility == View.VISIBLE || u.gapHint.visibility == View.VISIBLE
        val v = if (show) View.VISIBLE else View.GONE
        if (u.gapBox.visibility != v) {
            u.gapBox.visibility = v
            u.labels.visibility = if (!show && currentSeg != null) View.VISIBLE else View.INVISIBLE
            updateEmptyState()
        }
    }

    /** The strip's data at the current moment (spans open at "now" reach the moving now, as the strip draws them). */
    private fun cardData(): StripData? {
        val d = lastStripData ?: return null
        val now = System.currentTimeMillis()
        return if (now <= d.now) d else d.copy(now = now, maxMs = maxOf(d.maxMs, now))
    }

    /**
     * Where to hold the card when playback ran out at [at]: the live band's start when it begins within a seam,
     * [at] when a recording is open but starts later; UNSET when not recording or footage follows.
     */
    private fun liveTailWait(at: Long): Long {
        val d = cardData() ?: return UNSET
        val m = runCatching { StripModel.build(d) }.getOrNull() ?: return UNSET
        if (m.liveSince == Long.MIN_VALUE || at == UNSET) return UNSET
        // Only footage the player can play: an unanalysed clip (no end yet) is still to come, like the live one;
        // a clip that just failed is skipped by the player.
        val failed = failedSegId
        if (d.segments.any { it.available && it.id != failed && it.end != null && it.end > it.start && it.end > at + StripModel.SEAM_TOLERANCE_MS }) return UNSET
        if (m.liveSince - at in 0..StripModel.SEAM_TOLERANCE_MS) return m.liveSince
        // A recording that starts later only follows with continue across gaps on.
        return if (continueNext) at else UNSET
    }

    /** The time a sliver's bar covers at the current zoom (8 dp from its start) plus a finger's slop. */
    private fun sliverSlopMs(): Long {
        val s = strip ?: return StripModel.UNKNOWN_END_MS * 2
        val w = s.width
        if (w <= 0) return StripModel.UNKNOWN_END_MS * 2
        val msPerPx = s.spanMs.toDouble() / w
        return maxOf(StripModel.UNKNOWN_END_MS * 2, (msPerPx * 12 * resources.displayMetrics.density).toLong())
    }

    /** What the strip draws under the playhead, in words: the same gap, reason and coverage as its label. */
    private fun seekGapText(d: StripData, m: StripModel, at: Long): String {
        // A sliver named before stays named while it is unanalysed (a zoom change narrows the touch slop).
        val prevSliver = seekGapSliver?.takeIf { id -> d.segments.any { it.id == id && it.available && (it.end == null || it.end <= it.start) } }
        seekGapSliver = prevSliver
        // The red live band, whatever a neighbouring sliver's touch slop says at this zoom.
        if (m.liveSince != Long.MIN_VALUE && at >= m.liveSince) return getString(R.string.timeline_recording_now)
        val slop = sliverSlopMs()
        fun unknownLength(t: TlSegment) = t.end == null || t.end <= t.start
        val sliver = d.segments.firstOrNull { it.available && unknownLength(it) && at >= it.start - slop && at < it.start + slop }
            // An unanalysed clip's footage runs on past its sliver, up to the gap's end.
            ?: indexOfInterval(m.gapStart, m.gapEnd, at).takeIf { it >= 0 }?.let { g ->
                d.segments.firstOrNull { it.available && it.indexing && unknownLength(it) &&
                    m.gapStart[g] - (it.start + StripModel.UNKNOWN_END_MS) in -StripModel.SEAM_TOLERANCE_MS..StripModel.SEAM_TOLERANCE_MS }
            }
        seekGapSliver = sliver?.id ?: prevSliver
        if (sliver != null) {
            return getString(if (sliver.indexing) R.string.timeline_gap_indexing else R.string.timeline_gap_damaged)
        }
        if (indexOfInterval(m.offStart, m.offEnd, at) >= 0) {
            val seg = d.segments.firstOrNull { !it.available && at >= it.start - 1_000L &&
                at < maxOf(it.end ?: 0L, it.start + StripModel.UNKNOWN_END_MS) + 1_000L }
            val vol = when (seg?.storage) {
                "USB" -> R.string.timeline_storage_usb
                "INTERNAL" -> R.string.timeline_storage_internal
                else -> R.string.timeline_storage_sd
            }
            return getString(R.string.timeline_unavailable_volume, getString(vol))
        }
        if (indexOfInterval(m.armStart, m.armEnd, at) >= 0) return getString(R.string.timeline_armed_hint)
        val noFootage = getString(R.string.timeline_gap_no_footage)
        val g = indexOfInterval(m.gapStart, m.gapEnd, at)
        if (g < 0) return noFootage
        val reasonRes = GapReasons.reasonRes(d, m.gapSource[g], m.gapIndex[g])
        val dur = GapReasons.durationText(resources, m.gapEnd[g] - m.gapStart[g])
        return if (reasonRes == R.string.timeline_gap_no_footage) {
            getString(R.string.timeline_gap_fmt, noFootage, dur)
        } else {
            getString(R.string.timeline_gap_fmt, noFootage, GapReasons.label(resources, dur, getString(reasonRes)))
        }
    }

    private fun indexOfInterval(start: LongArray, end: LongArray, t: Long): Int {
        for (i in start.indices) if (t >= start[i] && t < end[i]) return i
        return -1
    }

    private fun handleGap(fromWall: Long, toWall: Long) {
        if (toWall <= fromWall) return
        // Reported for a position the screen has since jumped away from (e.g. a picked day aimed again): stale.
        if (kotlin.math.abs(positionMs - fromWall) > GAP_STALE_MS && kotlin.math.abs(positionMs - toWall) > GAP_STALE_MS) return
        if (continueNext) {
            strip?.glideTo(toWall)
            hideGapHint()
            return
        }
        val u = ui ?: return
        gapTargetMs = toWall
        u.gapHint.text = getString(R.string.timeline_next_event_in, durationText(toWall - fromWall))
        u.gapHint.visibility = View.VISIBLE
        syncGapBox(u)
        setControlsVisible(true)
    }

    private fun handleError(seg: TlSegment?, message: String) {
        failedSegId = seg?.id
        // The error lands after the state: playback that ran into a broken newest clip still waits for the next.
        if (endedWhilePlaying && !scrubbing && seekGapAt == UNSET && !liveFollow) {
            val wait = liveTailWait(positionMs)
            if (wait != UNSET) {
                handleSeekGap(wait, UNSET)
                liveFollow = true
            }
        }
        val gone = message == TimelinePlayer.MSG_GONE || message == TimelinePlayer.MSG_CHANGED
        // The strip still draws a vanished or rewritten clip: re-read its day (both, if it crosses midnight).
        if (gone && seg != null) {
            repo?.refreshAt(seg.start)
            seg.end?.let { repo?.refreshAt(it) }
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastErrorToastAt < ERROR_TOAST_GAP_MS) return
        lastErrorToastAt = now
        val text = if (message == TimelinePlayer.MSG_GONE) R.string.timeline_clip_gone else R.string.timeline_clip_error
        appToast?.show(getString(text), AppToast.Kind.ERROR)
    }

    private fun jump(wallMs: Long, play: Boolean) {
        val p = player ?: return
        val r = repo ?: return
        val t = wallMs.coerceAtMost(System.currentTimeMillis())
        autoPositionPending = false
        pendingDayMs = UNSET
        r.includeTime(t)
        hideGapHint()
        p.seekTo(t, true)
        if (play) p.play()
        positionMs = t
        strip?.glideTo(t)
        r.ensureLoaded(t - spanMs / 2, t + spanMs / 2)
        updateObjectChip(t)
        updateCurrentMoment(t)
        reportDay(t)
    }

    private fun prefetchSpecsAround(wallMs: Long) {
        val segs = repo?.currentSnapshot?.segments ?: return
        val near = segs.asSequence()
            .filter { it.available && it.end != null && it.end >= wallMs - SPEC_PREFETCH_BACK_MS }
            .take(SPEC_PREFETCH_COUNT)
            .toList()
        specs?.prefetch(near)
    }

    private fun loadMutePreference() {
        val ex = io ?: return
        val app = context?.applicationContext ?: return
        val gen = viewGen
        runCatching {
            ex.execute {
                val prefs = app.getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE)
                // Same rule as the clip player: an explicit choice, else unmuted when audio is recorded.
                val muted = if (prefs.contains(PLAYER_PREF_MUTED)) {
                    prefs.getBoolean(PLAYER_PREF_MUTED, true)
                } else {
                    !runCatching {
                        com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                            .optJSONObject("recording")?.optBoolean("audioEnabled", false) == true
                    }.getOrDefault(false)
                }
                main.post { if (gen == viewGen) player?.setMuted(muted) }
            }
        }
    }

    /** Lens strength is read off the UI thread; a change also drops cached lens specs. */
    private fun refreshDewarpStrength() {
        val ex = io ?: return
        val gen = viewGen
        runCatching {
            ex.execute {
                val s = PlaybackDewarp.liveStrength()
                main.post {
                    if (gen != viewGen || ui == null) return@post
                    val changed = dewarpStrength >= 0 && s != dewarpStrength
                    dewarpStrength = s
                    videoView?.setDewarpStrength(s)
                    if (!changed) return@post
                    specs?.invalidate()
                    val seg = currentSeg ?: return@post
                    specs?.load(seg) { spec ->
                        if (gen == viewGen && currentSeg?.id == seg.id) videoView?.setClipSpecNow(spec)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Strip
    // ---------------------------------------------------------------

    private val stripListener = object : TimelineStripView.Listener {
        override fun onScrubStart() {
            autoPositionPending = false
            pendingDayMs = UNSET
            scrubbing = true
            // Waiting showed Pause: a drag then resumes on release, as it does while playing.
            val waiting = liveFollow
            scrubWasPlaying = playing || waiting
            hideGapHint()
            main.removeCallbacks(hideControls)
            setControlsVisible(true)
            dimMoments(true)
            player?.beginScrub(waiting)
        }

        override fun onScrub(wallMs: Long) {
            positionMs = wallMs
            player?.scrubTo(wallMs)
            updateObjectChip(wallMs)
            reportDay(wallMs)
        }

        override fun onScrubEnd(wallMs: Long) {
            scrubbing = false
            positionMs = wallMs
            player?.endScrub(wallMs)
            dimMoments(false)
            updateCurrentMoment(wallMs)
            context?.let { TimelinePrefs.saveLastPosition(it, wallMs, System.currentTimeMillis()) }
            if (scrubWasPlaying) scheduleControlsHide()
        }

        override fun onMarkTapped(wallMs: Long, recId: String?) {
            jump(wallMs, true)
        }

        override fun onLiveTapped() {
            openLive()
        }

        override fun onSpanChanged(spanMs: Long) {
            this@TimelineFragment.spanMs = spanMs.coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
            syncZoomChecks(spanMs)
        }

        override fun onVisibleRange(fromMs: Long, toMs: Long) {
            repo?.ensureLoaded(fromMs, toMs)
        }

        override fun onSnapped(label: String?) {
            strip?.setPillLabel(label)
        }
    }

    // The strip's listener property; the single place to adapt if its API differs.
    private fun attachStripListener(s: TimelineStripView, l: TimelineStripView.Listener?) {
        s.listener = l
    }

    private fun pushStripData(snap: TimelineRepository.Snapshot?) {
        val s = strip ?: return
        val r = repo ?: return
        val now = System.currentTimeMillis()
        s.setData(
            lastStripData0(StripData(
                segments = snap?.segments ?: emptyList(),
                marks = snap?.marks ?: emptyList(),
                sessions = snap?.sessions ?: emptyList(),
                trips = snap?.trips ?: emptyList(),
                states = snap?.states ?: emptyList(),
                reaped = snap?.reaped ?: emptyList(),
                road = snap?.road ?: emptyList(),
                live = snap?.live ?: emptyList(),
                now = now,
                minMs = r.minMs.coerceAtMost(now),
                maxMs = now,
                tzId = snap?.tzId ?: r.timeZone.id,
                momentTimes = filteredMomentTimes(snap),
            ))
        )
    }

    private var lastStripData: StripData? = null

    private fun lastStripData0(d: StripData): StripData {
        lastStripData = d
        // A card raised before its cause changed (indexed, saved, card re-inserted) is re-read from the new data.
        if (seekGapAt != UNSET) main.post { refreshSeekGap() }
        return d
    }

    private fun refreshSeekGap() {
        val at = seekGapAt
        val u = ui ?: return
        if (at == UNSET || u.gapCard.visibility != View.VISIBLE) return
        val d = cardData() ?: return
        val m = runCatching { StripModel.build(d) }.getOrNull() ?: return
        // The sliver the card named is analysed now: the tap was on it (within its touch slop), so land on it.
        val named = seekGapSliver?.let { id -> d.segments.firstOrNull { it.id == id } }?.takeIf { !(liveFollow && it.id == failedSegId) }
        if (named != null && named.available && named.end != null && named.end > named.start && (at < named.start || at >= named.end)) {
            // While Play waits there it plays from the clip's start (no-op when the player's follow already did).
            val following = liveFollow
            hideGapHint()
            if (following) player?.playFrom(named.start) else player?.seekTo(named.start, true)
            return
        }
        // Waiting past a clip that failed: its footage under the card is not what Play waits for.
        val coversFailed = liveFollow && failedSegId != null &&
            d.segments.any { it.id == failedSegId && it.end != null && at >= it.start && at < it.end }
        if (!coversFailed && indexOfInterval(m.footStart, m.footEnd, at) >= 0 && d.segments.none { it.available && (it.end == null || it.end <= it.start) && at >= it.start && at < it.start + sliverSlopMs() }) {
            // Footage there now: drop the card and land on it, playing when Play waits there.
            val following = liveFollow
            hideGapHint()
            if (following) player?.playFrom(at) else player?.seekTo(at, true)
            return
        }
        u.gapCard.text = seekGapText(d, m, at)
        seekGapLive = m.liveSince != Long.MIN_VALUE && at >= m.liveSince
        // The recording stopped with nothing saved after the held moment (nor still being analysed): stop waiting.
        if (liveFollow && m.liveSince == Long.MIN_VALUE &&
            d.segments.none { it.available && (it.start >= at - StripModel.SEAM_TOLERANCE_MS || (it.indexing && (it.end == null || it.end <= it.start))) }
        ) {
            liveFollow = false
            player?.pause()
        }
    }

    private fun filteredMomentTimes(snap: TimelineRepository.Snapshot?): List<Long> {
        val segs = snap?.segments ?: return emptyList()
        return segs.asSequence()
            .filter { MomentText.isMoment(it.type) && passesFilter(it) }
            .map { it.start }
            .sorted()
            .toList()
    }

    private fun applyFilterToStrip() {
        val s = strip ?: return
        when (filterKey) {
            MomentText.FILTER_PEOPLE -> s.setFilter(setOf("person"), false)
            MomentText.FILTER_VEHICLES -> s.setFilter(setOf("vehicle", "bike"), false)
            MomentText.FILTER_ALERTS -> s.setFilter(null, true)
            else -> s.setFilter(null, false)
        }
    }

    private fun snapLabel(raw: String): String {
        val ctx = context ?: return raw
        return when (raw.trim().lowercase(Locale.ROOT)) {
            "person", "vehicle", "bike", "animal", "motion", "deterrent" ->
                MomentLabels.label(ctx, raw.trim().lowercase(Locale.ROOT))
            "pothole" -> getString(R.string.timeline_road_pothole)
            "breaker" -> getString(R.string.timeline_road_breaker)
            "rough" -> getString(R.string.timeline_road_rough)
            "live" -> getString(R.string.timeline_live)
            "drive", "trip" -> getString(R.string.timeline_drive)
            "replay" -> getString(R.string.timeline_moment_replay)
            "proximity" -> getString(R.string.timeline_moment_close_pass)
            else -> raw
        }
    }

    // ---------------------------------------------------------------
    // Repository
    // ---------------------------------------------------------------

    private val repoListener = object : TimelineRepository.Listener {
        override fun onTimelineData(snapshot: TimelineRepository.Snapshot) {
            if (ui == null) return
            indexSnapshot(snapshot)
            player?.setLive(snapshot.live.isNotEmpty())
            player?.setLoadedSpans(loadedSpans(snapshot))
            player?.setSegments(snapshot.segments)
            player?.setQuietHints(snapshot.marks, snapshot.road)
            pushStripData(snapshot)
            rebuildMoments()
            if (positionMs > 0L) prefetchSpecsAround(positionMs)
            maybeAutoPosition(snapshot)
            maybeAimPendingDay(snapshot)
            updateEmptyState()
            refreshDayPicker()
        }

        override fun onTimelineState(state: TimelineRepository.LoadState) {
            updateLoadChrome(state)
            updateEmptyState()
        }

        override fun onTimelineDays() {
            refreshDayPicker()
        }
    }

    private fun indexSnapshot(snap: TimelineRepository.Snapshot) {
        marks = snap.marks
        road = snap.road
        maxMarkSpan = snap.marks.maxOfOrNull { (it.t1 - it.t0).coerceIn(0L, MAX_MARK_SPAN_MS) } ?: 0L
        markKinds = snap.marks.groupBy({ it.rec }, { it.kind }).mapValues { it.value.toSet() }
        val known = TimeZone.getTimeZone(snap.tzId)
        if (known.id == snap.tzId && timeFormat?.timeZone?.id != known.id) {
            context?.let { timeFormat = newTimeFormat(it, known) }
        }
    }

    private fun maybeAutoPosition(snap: TimelineRepository.Snapshot) {
        if (!autoPositionPending) return
        val r = repo ?: return
        val now = System.currentTimeMillis()
        val today = r.dayStartOf(now)
        if (today !in snap.loadedDays) return
        autoPositionPending = false
        val tomorrow = r.nextDayStart(today)
        val todays = snap.segments.filter { it.start in today until tomorrow }
        val momentStarts = todays.filter { MomentText.isMoment(it.type) }.map { it.start }.sortedDescending()
        val latest = todays.filter { it.available && it.end != null }.maxByOrNull { it.end ?: 0L }
        val t = TimelineNav.autoPosition(momentStarts, latest?.start, latest?.end, now)
        if (t != positionMs) jump(t, false)
    }

    private fun updateLoadChrome(state: TimelineRepository.LoadState) {
        val u = ui ?: return
        val offline = state == TimelineRepository.LoadState.OFFLINE || state == TimelineRepository.LoadState.ERROR
        u.banner.visibility = if (offline) View.VISIBLE else View.GONE
        val loading = state == TimelineRepository.LoadState.LOADING && moments.isEmpty()
        u.skeleton.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading && !reducedMotion) {
            u.skeleton.animate().cancel()
            u.skeleton.alpha = 0.6f
            u.skeleton.animate().alpha(1f).setDuration(SKELETON_PULSE_MS).start()
        }
    }

    private fun updateEmptyState() {
        val u = ui ?: return
        val r = repo ?: return
        val wall = if (positionMs > 0L) positionMs else System.currentTimeMillis()
        val day = r.dayStartOf(wall)
        val next = r.nextDayStart(day)
        val loaded = r.isDayLoaded(day)
        val dayHasClips = r.currentSnapshot?.segments?.any { s ->
            val end = s.end ?: (s.start + 1000L)
            s.start < next && end > day
        } == true
        val empty = loaded && !dayHasClips
        val text = getString(R.string.timeline_empty_day, dayLabel(day))
        u.emptyDay.text = text
        // The gap card says more (reason, next event) and sits in the same place.
        u.emptyDay.visibility = if (empty && u.gapBox.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        u.momentsEmpty.text = text
        u.momentsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------
    // Moments
    // ---------------------------------------------------------------

    private fun bindMoments(u: Ui, ctx: Context) {
        val a = MomentsAdapter({ reducedMotion }) { row -> jump(row.start, true) }
        adapter = a
        u.moments.layoutManager = LinearLayoutManager(ctx)
        u.moments.itemAnimator = null
        u.moments.adapter = a
        u.moments.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) lastUserScrollAt = SystemClock.uptimeMillis()
            }
        })
    }

    private fun passesFilter(seg: TlSegment): Boolean {
        if (filterKey == MomentText.FILTER_ALL) return true
        val actors = MomentText.actorKinds(seg.classes, seg.counts, markKinds[seg.id] ?: emptySet())
        return MomentText.matchesFilter(filterKey, actors, seg.peakSeverity)
    }

    private fun rebuildMoments() {
        val r = repo ?: return
        val snap = r.currentSnapshot
        val wall = if (positionMs > 0L) positionMs else System.currentTimeMillis()
        val day = r.dayStartOf(wall)
        val next = r.nextDayStart(day)
        moments = snap?.segments.orEmpty()
            .filter { MomentText.isMoment(it.type) && it.start >= day && it.start < next && passesFilter(it) }
            .sortedByDescending { it.start }
        currentMomentId = MomentText.currentId(moments, wall, currentSeg?.id)
        submitRows()
        if (r.loadState != TimelineRepository.LoadState.LOADING || moments.isNotEmpty()) {
            ui?.skeleton?.visibility = View.GONE
        }
    }

    private fun updateCurrentMoment(wallMs: Long) {
        if (wallMs <= 0L) return
        val playingId = currentSeg?.id
        // Same list and clip, and no moment starts or ends since the last check: the answer stands.
        if (moments === momentKeyList && playingId == momentKeySeg && wallMs >= momentFrom && wallMs < momentTo) return
        momentKeyList = moments
        momentKeySeg = playingId
        var from = Long.MIN_VALUE
        var to = Long.MAX_VALUE
        if (playingId == null || moments.none { it.id == playingId }) {
            for (m in moments) {
                val end = m.end ?: continue
                if (m.start <= wallMs) from = maxOf(from, m.start) else to = minOf(to, m.start)
                if (end <= wallMs) from = maxOf(from, end) else to = minOf(to, end)
            }
        }
        momentFrom = from
        momentTo = to
        val id = MomentText.currentId(moments, wallMs, playingId)
        if (id == currentMomentId) return
        currentMomentId = id
        submitRows()
    }

    private fun refreshRowsIfCurrent() {
        if (currentMomentId != null) submitRows()
    }

    private fun submitRows() {
        val u = ui ?: return
        val a = adapter ?: return
        val ctx = context ?: return
        val rows = moments.map { toRow(ctx, it) }
        a.submitList(rows) { maybeAutoScroll() }
        u.momentsCount.text = resources.getQuantityString(R.plurals.timeline_moments_count, rows.size, rows.size)
    }

    private fun toRow(ctx: Context, seg: TlSegment): MomentRow {
        val keys = MomentText.kindKeys(seg.type, seg.classes, seg.counts, markKinds[seg.id] ?: emptySet())
        val kind = MomentLabels.kind(ctx, keys)
        val cam = MomentLabels.camera(ctx, MomentText.firstCamera(seg.cameras))
        val duration = MomentText.durationLabel(seg.end?.let { it - seg.start })
        val subtitle = MomentLabels.join(ctx, MomentText.subtitleParts(cam, seg.place, duration))
        val time = formatTime(seg.start)
        val current = seg.id == currentMomentId
        val isPlaying = current && playing
        val status = when (MomentText.status(seg.peakSeverity, isPlaying)) {
            MomentText.Status.PLAYING -> getString(R.string.timeline_playing)
            MomentText.Status.CRITICAL -> getString(R.string.timeline_critical)
            MomentText.Status.ALERT -> getString(R.string.timeline_alert)
            MomentText.Status.NONE -> null
        }
        val description = listOfNotNull(time, kind, subtitle.takeIf { it.isNotEmpty() }, status).joinToString(", ")
        return MomentRow(
            id = seg.id,
            fp = seg.fp,
            start = seg.start,
            end = seg.end,
            thumbUrl = seg.thumbUrl,
            time = time,
            kind = kind,
            subtitle = subtitle,
            severity = seg.peakSeverity,
            current = current,
            playing = isPlaying,
            description = description,
        )
    }

    // Keeps the playing row in view unless the user scrolled within the last 5 s.
    private fun maybeAutoScroll() {
        val u = ui ?: return
        val id = currentMomentId ?: return
        if (SystemClock.uptimeMillis() - lastUserScrollAt < USER_SCROLL_HOLD_MS) return
        if (u.moments.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val pos = adapter?.positionOf(id) ?: return
        if (pos < 0) return
        val lm = u.moments.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstCompletelyVisibleItemPosition()
        val last = lm.findLastCompletelyVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && pos in first..last) return
        if (reducedMotion) lm.scrollToPositionWithOffset(pos, u.moments.height / 3)
        else u.moments.smoothScrollToPosition(pos)
    }

    private fun dimMoments(on: Boolean) {
        val pane = ui?.momentsPane ?: return
        pane.animate().cancel()
        pane.animate().alpha(if (on) SCRUB_DIM_ALPHA else 1f)
            .setDuration(if (reducedMotion) 0L else DIM_MS)
            .start()
    }

    // ---------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------

    private fun bindControls(u: Ui) {
        u.playPause.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (liveFollow) {
                // Pause while waiting for the saved clip: nothing starts by itself (the card stays as a note).
                liveFollow = false
                p.pause()
                return@setOnClickListener
            }
            val held = seekGapAt
            if (!playing && held != UNSET && seekGapLive) {
                // Still being recorded here: start it as soon as its saved clip appears (the card stays).
                liveFollow = true
                p.followFrom(held)
                return@setOnClickListener
            }
            if (!playing && playerState == TimelinePlayer.State.ENDED) {
                val wait = liveTailWait(positionMs)
                if (wait != UNSET) {
                    // Ran out at the newest clip while one is recording: say so; the saved clip starts by itself.
                    handleSeekGap(wait, UNSET)
                    liveFollow = true
                    p.followFrom(positionMs, inside = false)
                    return@setOnClickListener
                }
            }
            hideGapHint()
            if (playing) p.pause() else p.play()
        }
        u.prev.setOnClickListener {
            TimelineNav.prevMoment(filteredMomentTimes(repo?.currentSnapshot), currentWallMs)
                ?.let { jump(it, playing) }
        }
        u.next.setOnClickListener {
            TimelineNav.nextMoment(filteredMomentTimes(repo?.currentSnapshot), currentWallMs)
                ?.let { jump(it, playing) }
        }
        u.speed.setOnClickListener { anchor ->
            val menu = speedMenu ?: return@setOnClickListener
            main.removeCallbacks(hideControls)
            val state = SpeedMenu.State(speed, skipQuiet, continueNext)
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                menu.showPopover(anchor, state)
            } else {
                menu.showSheet(state)
            }
        }
        u.fullscreen.setOnClickListener { setTheater(!theaterMode) }
        u.share.setOnClickListener { shareCurrentSegment() }
        u.gapHint.setOnClickListener {
            val target = gapTargetMs
            if (target > 0L) jump(target, true)
        }
        u.sourceChip.setOnClickListener {
            val toOem = !isOemFamily(activeFamily)
            preferSource = if (toOem) TYPE_OEM else null
            player?.setPreferredSource(preferSource)
            context?.let { TimelinePrefs.setPreferredSource(it, preferSource) }
        }
        u.retry.setOnClickListener { repo?.retry() }
        for (b in listOf(u.prev, u.playPause, u.next, u.fullscreen, u.share, u.speed, u.sourceChip)) {
            b.setOnTouchListener { v, ev -> pressFeedback(v, ev); false }
        }
        refreshFullscreenButton()
    }

    private fun setTheater(on: Boolean) {
        if (theaterMode == on) return
        theaterMode = on
        theaterBack.isEnabled = on
        (activity as? MainActivity)?.setContentImmersive(on)
        val u = ui
        if (u == null) {
            theaterParentGravity = null
            return
        }
        u.panel.visibility = if (on) View.GONE else View.VISIBLE
        u.momentsPane.visibility = if (on) View.GONE else View.VISIBLE
        u.playerBox.theater = on
        // Landscape: the player shares a row with the moments pane; centre it while that is hidden.
        (u.playerBox.parent as? LinearLayout)?.takeIf { it.orientation == LinearLayout.HORIZONTAL }?.let { row ->
            if (on) {
                theaterParentGravity = row.gravity
                row.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            } else {
                theaterParentGravity?.let { row.gravity = it }
                theaterParentGravity = null
            }
        }
        refreshFullscreenButton()
        u.root.requestLayout()
    }

    private fun refreshFullscreenButton() {
        val b = ui?.fullscreen ?: return
        b.setImageResource(if (theaterMode) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        b.contentDescription = getString(
            if (theaterMode) R.string.timeline_cd_exit_fullscreen else R.string.timeline_cd_fullscreen,
        )
    }

    private fun shareCurrentSegment() {
        val seg = currentSeg
        if (seg == null || !seg.available) {
            appToast?.show(getString(R.string.timeline_share_unavailable), AppToast.Kind.WARNING)
            return
        }
        if (seg.end == null) {
            appToast?.show(getString(R.string.timeline_share_recording), AppToast.Kind.WARNING)
            return
        }
        if (shareInFlight) return
        val exec = shareExec ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "TimelineShare").apply { isDaemon = true }
        }.also { shareExec = it }
        shareInFlight = true
        val gen = viewGen
        runCatching {
            exec.execute {
                val file = try { downloadSegmentForShare(seg) } finally { shareInFlight = false }
                main.post {
                    if (gen != viewGen || ui == null) return@post
                    if (file == null) {
                        appToast?.show(getString(R.string.timeline_share_failed), AppToast.Kind.ERROR)
                    } else {
                        shareVideoFile(file)
                    }
                }
            }
        }.onFailure { shareInFlight = false }
    }

    private fun downloadSegmentForShare(seg: TlSegment): File? {
        val ctx = context?.applicationContext ?: return null
        val dir = File(ctx.cacheDir, "timeline-share")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val safeId = seg.id.replace(Regex("[^a-f0-9]"), "")
        if (safeId.isEmpty()) return null
        val target = File(dir, "$safeId.mp4")
        if (seg.sizeBytes > 0L && target.isFile && target.length() == seg.sizeBytes) return target
        dir.listFiles()?.forEach { if (it != target) it.delete() }
        target.delete()
        val partial = File(dir, "${target.name}.part")
        partial.delete()
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(
                "/video/id/${URLEncoder.encode(seg.id, "UTF-8")}",
                "GET",
                connectTimeoutMs = 3_000,
                readTimeoutMs = 300_000,
            )
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            val expected = conn.contentLengthLong
            if (expected > 0L && partial.length() != expected) return null
            if (!partial.renameTo(target)) {
                target.delete()
                if (!partial.renameTo(target)) return null
            }
            target
        } catch (_: Throwable) {
            null
        } finally {
            partial.delete()
            conn?.disconnect()
        }
    }

    private fun shareVideoFile(file: File) {
        val ctx = context ?: return
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
        } catch (_: Exception) {
            appToast?.show(getString(R.string.timeline_share_failed), AppToast.Kind.ERROR)
        }
    }

    private fun applySpeedState(s: SpeedMenu.State) {
        val ctx = context ?: return
        val p = player ?: return
        if (s.speed != speed) {
            speed = s.speed
            p.setSpeed(speed)
            strip?.setPlayback(currentWallMs, playing, speed)
        }
        if (s.skipQuiet != skipQuiet) {
            skipQuiet = s.skipQuiet
            p.setSkipQuietDriving(skipQuiet)
            TimelinePrefs.setSkipQuiet(ctx, skipQuiet)
        }
        if (s.continueNext != continueNext) {
            continueNext = s.continueNext
            p.setContinueAcrossGaps(continueNext)
            TimelinePrefs.setContinueNext(ctx, continueNext)
            if (continueNext) hideGapHint()
        }
        updateSpeedButton()
    }

    private fun updateSpeedButton() {
        val b = ui?.speed ?: return
        // Skip quiet driving sets the speed itself (8× quiet drives, 1× events): say so instead of a stale pick.
        val label = if (skipQuiet) getString(R.string.timeline_speed_auto) else SpeedMenu.speedLabel(speed)
        b.text = label
        b.contentDescription = getString(R.string.timeline_cd_speed, label)
        val fast = speed > 1 || skipQuiet
        b.setBackgroundResource(if (fast) R.drawable.tl_speed_active else R.drawable.tl_glass_ripple)
        b.setTextColor(
            if (fast) SPEED_ACTIVE_COLOR else ContextCompat.getColor(b.context, R.color.rec_player_on_glass)
        )
    }

    private fun hideGapHint() {
        gapTargetMs = UNSET
        seekGapAt = UNSET
        seekGapLive = false
        liveFollow = false
        seekGapSliver = null
        ui?.gapHint?.visibility = View.GONE
        ui?.let { u ->
            if (u.gapCard.visibility == View.VISIBLE) {
                u.gapCard.visibility = View.GONE
                if (currentSeg != null) u.labels.visibility = View.VISIBLE
            }
            syncGapBox(u)
        }
        videoView?.alpha = 1f
    }

    private fun scheduleControlsHide() {
        main.removeCallbacks(hideControls)
        if (!playing || scrubbing || speedMenu?.isShowing == true || touchExploration()) return
        main.postDelayed(hideControls, CONTROLS_HIDE_MS)
    }

    private fun setControlsVisible(show: Boolean) {
        val u = ui ?: return
        val c = u.controls
        c.animate().cancel()
        val duration = if (reducedMotion) REDUCED_FADE_MS else FADE_MS
        if (show) {
            c.visibility = View.VISIBLE
            c.animate().alpha(1f).setDuration(duration).start()
        } else {
            c.animate().alpha(0f).setDuration(duration)
                .withEndAction { if (ui === u && playing) c.visibility = View.INVISIBLE }
                .start()
        }
        layoutObjectChip(show)
    }

    private fun touchExploration(): Boolean {
        val am = context?.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return am?.isTouchExplorationEnabled == true
    }

    private fun pressFeedback(v: View, ev: MotionEvent) {
        if (reducedMotion) return
        val target = when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> PRESS_SCALE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
            else -> return
        }
        v.animate().scaleX(target).scaleY(target).setDuration(PRESS_MS).start()
    }

    private fun openLive() {
        player?.pause()
        runCatching {
            findNavController().navigate(R.id.liveViewFragment, null, NavOptionsExt.m3FadeThrough())
        }
    }

    // ---------------------------------------------------------------
    // Filters + zoom
    // ---------------------------------------------------------------

    private fun bindFilters(u: Ui) {
        suppressGroupEvents = true
        u.filterGroup.check(
            when (filterKey) {
                MomentText.FILTER_PEOPLE -> R.id.tlFilterPeople
                MomentText.FILTER_VEHICLES -> R.id.tlFilterVehicles
                MomentText.FILTER_ALERTS -> R.id.tlFilterAlerts
                else -> R.id.tlFilterAll
            }
        )
        suppressGroupEvents = false
        u.filterGroup.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked || suppressGroupEvents) return@addOnButtonCheckedListener
            filterKey = when (id) {
                R.id.tlFilterPeople -> MomentText.FILTER_PEOPLE
                R.id.tlFilterVehicles -> MomentText.FILTER_VEHICLES
                R.id.tlFilterAlerts -> MomentText.FILTER_ALERTS
                else -> MomentText.FILTER_ALL
            }
            applyFilterToStrip()
            pushStripData(repo?.currentSnapshot)
            rebuildMoments()
        }
        u.zoomGroup.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked || suppressGroupEvents) return@addOnButtonCheckedListener
            val span = when (id) {
                R.id.tlZoomDay -> DAY_SPAN_MS
                R.id.tlZoom6h -> 6L * HOUR_MS
                R.id.tlZoom1h -> HOUR_MS
                else -> 10L * MINUTE_MS
            }
            spanMs = span
            strip?.setSpan(span, !reducedMotion)
        }
    }

    private fun syncZoomChecks(span: Long) {
        val group = ui?.zoomGroup ?: return
        val id = PRESETS.entries.firstOrNull { (_, preset) ->
            kotlin.math.abs(span - preset) <= preset / 20
        }?.key
        suppressGroupEvents = true
        if (id == null) group.clearChecked() else if (group.checkedButtonId != id) group.check(id)
        suppressGroupEvents = false
    }

    // ---------------------------------------------------------------
    // Player overlay
    // ---------------------------------------------------------------

    private fun bindPlayerBox(u: Ui) {
        u.playerBox.onTouchObserved = { ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                setControlsVisible(true)
                scheduleControlsHide()
            }
        }
        // While the pill is hidden the first tap only brings it back.
        u.playerBox.revealOnTap = { u.controls.visibility != View.VISIBLE || u.controls.alpha < 0.5f }
        u.playerBox.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) layoutObjectChip(u.controls.visibility == View.VISIBLE)
        }
    }

    /** The view already switched; it reports the camera now shown (ALL after a second tap). */
    private fun onCameraTapped(q: ZoomableVideoView.Quadrant) {
        camera = q
        val labels = ui?.labels ?: return
        main.removeCallbacks(showLabels)
        labels.animate().cancel()
        labels.alpha = 0f
        main.postDelayed(showLabels, if (reducedMotion) 0L else CAMERA_SETTLE_MS)
    }

    private val percentFormat: NumberFormat by lazy { NumberFormat.getPercentInstance() }

    private fun updateObjectChip(wallMs: Long) {
        val u = ui ?: return
        val ctx = context ?: return
        val mark = markAt(wallMs)
        val road = if (mark == null) roadAt(wallMs) else null
        // The same mark (or road event) as the last update: the chip already says it.
        if (chipKnown && mark === chipMark && road === chipRoad) return
        chipKnown = true
        chipMark = mark
        chipRoad = road
        var text: String? = null
        var icon = 0
        if (mark != null) {
            val kind = MomentLabels.label(ctx, mark.kind)
            val conf = mark.conf?.let { if (it > 1.0) it / 100.0 else it }?.coerceIn(0.0, 1.0)
            text = if (conf != null) {
                getString(R.string.timeline_gap_fmt, kind, percentFormat.format(conf))
            } else {
                kind
            }
            icon = kindIcon(mark.kind)
        } else if (road != null) {
            text = snapLabel(road.kind)
            icon = roadIcon(road.kind)
        }
        val chip = u.objectChip
        val label = text
        if (label == null || icon == 0) {
            if (chip.visibility != View.GONE) chip.visibility = View.GONE
            chip.tag = null
            chipLift = Float.NaN
            return
        }
        if (chip.tag == label && chip.visibility == View.VISIBLE) return
        chip.tag = label
        chip.text = label
        val d = ContextCompat.getDrawable(ctx, icon)?.mutate()
        if (d != null) {
            val size = (CHIP_ICON_DP * resources.displayMetrics.density).toInt()
            d.setTint(Color.WHITE)
            d.setBounds(0, 0, size, size)
        }
        chip.setCompoundDrawablesRelative(d, null, null, null)
        chip.visibility = View.VISIBLE
        chipLift = Float.NaN
        layoutObjectChip(u.controls.visibility == View.VISIBLE && u.controls.alpha > 0f)
    }

    // A narrow player has no room for both the pill and the chip at the bottom: the chip rises above the pill.
    private fun layoutObjectChip(controlsShown: Boolean) {
        val u = ui ?: return
        val chip = u.objectChip
        if (chip.visibility != View.VISIBLE) return
        chip.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val c = u.controls
        val needed = c.width + 2 * (chip.measuredWidth + chip.marginEndPx())
        val crowded = u.playerBox.width in 1 until needed
        val lift = if (crowded && controlsShown) {
            -(c.height + c.marginBottomPx() - chip.marginBottomPx() + CHIP_GAP_DP * resources.displayMetrics.density)
        } else {
            0f
        }
        if (lift == chipLift) return
        chipLift = lift
        chip.alpha = 1f
        chip.animate().translationY(lift).setDuration(if (reducedMotion) 0L else FADE_MS).start()
    }

    private fun View.marginEndPx(): Int = (layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0

    private fun View.marginBottomPx(): Int = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

    private fun markAt(wallMs: Long): TlMark? {
        val list = marks
        if (list.isEmpty()) return null
        var lo = 0
        var hi = list.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].t0 <= wallMs + MARK_SLACK_MS) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        var best: TlMark? = null
        var i = idx
        while (i >= 0 && list[i].t0 >= wallMs - maxMarkSpan - MARK_SLACK_MS) {
            val m = list[i]
            if (m.t1 + MARK_SLACK_MS >= wallMs) {
                val b = best
                if (b == null || (m.alert && !b.alert) || (m.alert == b.alert && (m.conf ?: 0.0) > (b.conf ?: 0.0))) {
                    best = m
                }
            }
            i--
        }
        return best
    }

    private fun roadAt(wallMs: Long): TlRoad? =
        road.firstOrNull { kotlin.math.abs(it.t - wallMs) <= ROAD_CHIP_MS }

    private fun kindIcon(kind: String): Int = when (kind) {
        "person" -> R.drawable.ic_person
        "vehicle" -> R.drawable.ic_directions_car
        "bike" -> R.drawable.ic_pedal_bike
        "animal" -> R.drawable.ic_pets
        "deterrent" -> R.drawable.ic_warning
        else -> R.drawable.ic_sentry
    }

    private fun roadIcon(kind: String): Int = when (kind) {
        "pothole" -> R.drawable.ic_hazard_pothole
        "breaker" -> R.drawable.ic_hazard_breaker
        "rough" -> R.drawable.ic_hazard_rough
        else -> R.drawable.ic_hazard_unknown
    }

    // ---------------------------------------------------------------
    // Positions, days, formatting
    // ---------------------------------------------------------------

    /** Start time and whether to play: explicit target, retained, remembered, else picked on load. */
    private fun resolveStart(ctx: Context): Pair<Long, Boolean> {
        val now = System.currentTimeMillis()
        val args = arguments
        var argTime: Long? = null
        var argEnd: Long? = null
        if (!argsConsumed) {
            argTime = args?.getLong(ARG_TIME_MS, UNSET)?.takeIf { it > 0L }
            argEnd = args?.getLong(ARG_END_MS, UNSET)?.takeIf { it > 0L }
            argsConsumed = true
        }
        val pending = pendingJumpMs.takeIf { it > 0L }
        val pendingPlay = pendingJumpPlay
        pendingJumpMs = UNSET
        pendingJumpPlay = false
        val explicit = pending ?: argTime
        if (explicit != null && argTime != null && argEnd != null && pending == null && argEnd > argTime) {
            spanMs = ((argEnd - argTime) * END_SPAN_FACTOR).toLong().coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
        }
        val retained = retainedWallMs.takeIf { it > 0L }
        val remembered = if (explicit == null && retained == null) TimelinePrefs.lastPosition(ctx, now) else null
        val start = TimelineNav.initialPosition(explicit, null, retained, remembered)
        val play = when {
            pending != null -> pendingPlay
            explicit != null -> true
            retained != null -> retainedPlaying
            else -> false
        }
        retainedPlaying = false
        if (start == null) {
            autoPositionPending = true
            return Pair(now - AUTO_FALLBACK_MS, false)
        }
        autoPositionPending = false
        return Pair(start.coerceAtMost(now), play)
    }

    private fun reportDay(wallMs: Long) {
        val r = repo ?: return
        if (wallMs <= 0L) return
        // Still inside the reported day (same zone): no calendar work on the position path.
        if (lastDayReported != UNSET && wallMs >= lastDayReported && wallMs < lastDayEnd && r.timeZone === lastDayTz) return
        val day = r.dayStartOf(wallMs)
        lastDayTz = r.timeZone
        lastDayEnd = r.nextDayStart(day)
        if (day == lastDayReported) return
        lastDayReported = day
        onDayChanged?.invoke(day)
        rebuildMoments()
        updateEmptyState()
    }

    private fun newTimeFormat(ctx: Context, tz: TimeZone): SimpleDateFormat {
        val locale = Locale.getDefault()
        val skeleton = if (DateFormat.is24HourFormat(ctx)) "Hm" else "hm"
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).apply { timeZone = tz }
    }

    private fun formatTime(ms: Long): String = timeFormat?.format(Date(ms)) ?: ""

    private fun dayLabel(dayStart: Long): String {
        val locale = Locale.getDefault()
        val fmt = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
        fmt.timeZone = repo?.timeZone ?: TimeZone.getDefault()
        return fmt.format(Date(dayStart + HOUR_MS * 12))
    }

    private fun durationText(ms: Long): String {
        val totalMin = ms / MINUTE_MS
        return when {
            totalMin >= 60 -> getString(R.string.timeline_duration_hm, (totalMin / 60).toInt(), (totalMin % 60).toInt())
            totalMin >= 1 -> getString(R.string.timeline_duration_m, totalMin.toInt())
            else -> getString(R.string.timeline_duration_s, (ms / 1000L).coerceAtLeast(1L).toInt())
        }
    }

    private fun cameraNames(ctx: Context): Map<ZoomableVideoView.Quadrant, String> {
        val locale = Locale.getDefault()
        return mapOf(
            ZoomableVideoView.Quadrant.FRONT to ctx.getString(R.string.timeline_cam_front).uppercase(locale),
            ZoomableVideoView.Quadrant.RIGHT to ctx.getString(R.string.timeline_cam_right).uppercase(locale),
            ZoomableVideoView.Quadrant.REAR to ctx.getString(R.string.timeline_cam_rear).uppercase(locale),
            ZoomableVideoView.Quadrant.LEFT to ctx.getString(R.string.timeline_cam_left).uppercase(locale),
        )
    }

    private fun cameraName(q: ZoomableVideoView.Quadrant): String? = when (q) {
        ZoomableVideoView.Quadrant.FRONT -> getString(R.string.timeline_cam_front)
        ZoomableVideoView.Quadrant.RIGHT -> getString(R.string.timeline_cam_right)
        ZoomableVideoView.Quadrant.REAR -> getString(R.string.timeline_cam_rear)
        ZoomableVideoView.Quadrant.LEFT -> getString(R.string.timeline_cam_left)
        ZoomableVideoView.Quadrant.ALL -> null
    }

    private fun isOemFamily(family: String?): Boolean =
        family?.lowercase(Locale.ROOT)?.contains("oem") == true

    private fun isReducedMotion(ctx: Context): Boolean = runCatching {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    companion object {
        const val ARG_TIME_MS = TimelineLinks.ARG_TIME_MS
        const val ARG_END_MS = TimelineLinks.ARG_END_MS

        private const val KEY_WALL = "tl_wall_ms"
        private const val KEY_PLAYING = "tl_playing"
        private const val KEY_SPEED = "tl_speed"
        private const val KEY_FILTER = "tl_filter"
        private const val KEY_SPAN = "tl_span_ms"
        private const val KEY_CAMERA = "tl_camera"
        private const val KEY_ARGS_CONSUMED = "tl_args_consumed"
        private const val KEY_DAY_PICKER = "tl_day_picker_month"
        private const val PLAYER_PREFS = "video_player_prefs"
        private const val PLAYER_PREF_MUTED = "muted"
        private const val TYPE_OEM = "oemDashcam"
        private const val GAP_VIDEO_ALPHA = 0.28f
        // Accent over the always-dark glass pill.
        private const val SPEED_ACTIVE_COLOR = 0xFF5DDBB6.toInt()

        private const val UNSET = -1L
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60L * MINUTE_MS
        private const val DAY_SPAN_MS = 24L * HOUR_MS
        private const val DEFAULT_SPAN_MS = 6L * HOUR_MS
        private const val MIN_SPAN_MS = 2L * MINUTE_MS
        private const val MAX_SPAN_MS = DAY_SPAN_MS
        private const val END_SPAN_FACTOR = 2.2
        private const val AUTO_FALLBACK_MS = 60_000L
        private const val CONTROLS_HIDE_MS = 5_000L
        private const val GAP_FLASH_MS = 2_500L
        private const val USER_SCROLL_HOLD_MS = 5_000L
        private const val BUFFERING_DELAY_MS = 300L
        private const val ERROR_TOAST_GAP_MS = 4_000L
        private const val GAP_SLACK_MS = 1_500L
        private const val GAP_STALE_MS = 10_000L
        private const val FADE_MS = 200L
        private const val REDUCED_FADE_MS = 150L
        private const val DIM_MS = 160L
        private const val PRESS_MS = 100L
        private const val PRESS_SCALE = 0.97f
        private const val SCRUB_DIM_ALPHA = 0.45f
        private const val CAMERA_SETTLE_MS = 350L
        private const val SKELETON_PULSE_MS = 600L
        private const val MARK_SLACK_MS = 300L
        private const val MAX_MARK_SPAN_MS = 10L * MINUTE_MS
        private const val ROAD_CHIP_MS = 2_000L
        private const val CHIP_ICON_DP = 18
        private const val CHIP_GAP_DP = 8
        private const val SPEC_PREFETCH_BACK_MS = 60_000L
        private const val SPEC_PREFETCH_COUNT = 6
        private val SPEEDS = setOf(1, 2, 4, 8)
        private val PRESETS = linkedMapOf(
            R.id.tlZoomDay to DAY_SPAN_MS,
            R.id.tlZoom6h to 6L * HOUR_MS,
            R.id.tlZoom1h to HOUR_MS,
            R.id.tlZoom10m to 10L * MINUTE_MS,
        )

        fun newInstance(timeMs: Long, endMs: Long): TimelineFragment = TimelineFragment().apply {
            arguments = Bundle().apply {
                if (timeMs > 0L) putLong(ARG_TIME_MS, timeMs)
                if (endMs > 0L) putLong(ARG_END_MS, endMs)
            }
        }
    }
}

/**
 * Player frame: 4:3 driven by height in landscape (capped at 58% of the row),
 * by width elsewhere. Observes touches without consuming them.
 */
class TlPlayerBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onTouchObserved: ((MotionEvent) -> Unit)? = null
    var revealOnTap: (() -> Boolean)? = null

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var swallowTap = false
    private var multiTouch = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    init {
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swallowTap = revealOnTap?.invoke() == true
                multiTouch = false
                downX = ev.x
                downY = ev.y
                downAt = ev.eventTime
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiTouch = true
        }
        onTouchObserved?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }

    // A quick single tap is taken back at UP, so children see CANCEL and drags still pass.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP && swallowTap && !multiTouch &&
            ev.eventTime - downAt < android.view.ViewConfiguration.getLongPressTimeout() &&
            kotlin.math.hypot(ev.x - downX, ev.y - downY) < touchSlop
        ) {
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (swallowTap) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowTap = false
            return true
        }
        return super.onTouchEvent(ev)
    }

    /** Fullscreen timeline: the player may take the whole row/column. */
    var theater = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val widthFraction = if (theater) 1f else MAX_WIDTH_FRACTION
        val heightFraction = if (theater) 1f else MAX_HEIGHT_FRACTION
        val w: Int
        val h: Int
        if (landscape && hMode == MeasureSpec.EXACTLY) {
            h = hSize
            val byHeight = h * 4 / 3
            w = if (wMode == MeasureSpec.UNSPECIFIED) byHeight else minOf(byHeight, (wSize * widthFraction).toInt())
        } else {
            w = if (wMode == MeasureSpec.UNSPECIFIED) hSize * 4 / 3 else wSize
            val byWidth = w * 3 / 4
            h = if (hMode == MeasureSpec.UNSPECIFIED) byWidth else minOf(byWidth, (hSize * heightFraction).toInt())
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h.coerceAtLeast(0), MeasureSpec.EXACTLY),
        )
    }

    private companion object {
        const val MAX_WIDTH_FRACTION = 0.58f
        const val MAX_HEIGHT_FRACTION = 0.55f
    }
}

/** Small camera names at the top-left of each visible tile, as placed by the video view. */
class TlCameraLabelsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.06f
        setShadowLayer(4f * density, 0f, density, 0x99000000.toInt())
    }
    private val names = EnumMap<ZoomableVideoView.Quadrant, String>(ZoomableVideoView.Quadrant::class.java)
    private var tiles: List<CameraTile> = emptyList()
    private val pad = 10f * density
    private val minVisible = 48f * density

    fun setNames(map: Map<ZoomableVideoView.Quadrant, String>) {
        names.clear()
        names.putAll(map)
        invalidate()
    }

    fun setTiles(list: List<CameraTile>) {
        tiles = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for (t in tiles) {
            val text = names[t.camera] ?: continue
            val left = maxOf(t.left, 0f)
            val top = maxOf(t.top, 0f)
            // Skip a tile zoomed or panned mostly out of view.
            if (minOf(t.right, w) - left < minVisible || minOf(t.bottom, h) - top < minVisible) continue
            canvas.drawText(text, left + pad, top + pad - paint.ascent(), paint)
        }
    }
}

/** What the day picker shows: the day list, loaded days with clips, the navigable range and the shown day. */
internal class TlDayPickerData(
    val stats: Map<Long, TlDay>?,
    val footage: Set<Long>,
    val minDay: Long,
    val today: Long,
    val selected: Long,
)

// The date pill's day picker: a month grid where days with footage are enabled and dotted (amber with alerts)
// and the shown day is filled. A tap opens that day; there is no time dial, the strip picks the time.
internal class TlDayPicker(
    private val ctx: Context,
    val tz: TimeZone,
    private val onPick: (Long) -> Unit,
) {
    /** Start of the month on screen. */
    var month = -1L
        private set
    val isShowing: Boolean get() = dialog?.isShowing == true

    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private var data: TlDayPickerData? = null
    private var title: TextView? = null
    private var prev: View? = null
    private var next: View? = null
    private val weekdays = ArrayList<TextView>(7)
    private val cells = ArrayList<TlDayCell>(GRID_ROWS * 7)
    private val locale = Locale.getDefault()
    private val monthFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMMy"), locale).apply { timeZone = tz }
    private val dateFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMy"), locale).apply { timeZone = tz }
    private val weekdayFormat = SimpleDateFormat("EEE", locale).apply { timeZone = tz }
    private val numberFormat = NumberFormat.getIntegerInstance(locale)

    fun show(d: TlDayPickerData, monthStart: Long) {
        data = d
        val builder = MaterialAlertDialogBuilder(ctx)
        val themed = builder.context
        val content = LayoutInflater.from(themed).inflate(R.layout.dialog_timeline_days, null)
        bind(content)
        month = clampMonth(if (monthStart > 0L) monthStart else monthStartOf(d.selected))
        render()
        val dlg = builder.setView(content).create()
        dialog = dlg
        dlg.show()
        // Seven 64 dp columns plus the card's padding and insets, never wider than the screen.
        val dm = themed.resources.displayMetrics
        val width = minOf((DIALOG_WIDTH_DP * dm.density).toInt(), dm.widthPixels - (32 * dm.density).toInt())
        dlg.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun update(d: TlDayPickerData) {
        data = d
        if (month > 0L) month = clampMonth(month)
        render()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun bind(root: View) {
        val c = root.context
        val density = c.resources.displayMetrics.density
        title = root.findViewById(R.id.tlDaysMonth)
        prev = root.findViewById<View>(R.id.tlDaysPrev).apply { setOnClickListener { stepMonth(-1) } }
        next = root.findViewById<View>(R.id.tlDaysNext).apply { setOnClickListener { stepMonth(1) } }
        root.findViewById<View>(R.id.tlDaysCancel).setOnClickListener { dismiss() }
        val muted = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val weekRow = root.findViewById<LinearLayout>(R.id.tlDaysWeekdays)
        repeat(7) {
            val t = TextView(c).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            weekRow.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            weekdays.add(t)
        }
        val grid = root.findViewById<LinearLayout>(R.id.tlDaysGrid)
        repeat(GRID_ROWS) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (CELL_HEIGHT_DP * density).toInt()))
            repeat(7) {
                val cell = TlDayCell(c)
                cell.setOnClickListener { v ->
                    val day = (v as TlDayCell).day
                    if (day > 0L) {
                        dismiss()
                        onPick(day)
                    }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                cells.add(cell)
            }
        }
        root.findViewById<TextView>(R.id.tlDaysLegendFootage)
            .setCompoundDrawablesRelativeWithIntrinsicBounds(legendDot(density, muted), null, null, null)
        root.findViewById<TextView>(R.id.tlDaysLegendAlerts)
            .setCompoundDrawablesRelativeWithIntrinsicBounds(legendDot(density, TlDayCell.alertColor(c)), null, null, null)
    }

    private fun render() {
        val d = data ?: return
        if (cells.isEmpty() || month <= 0L) return
        val res = ctx.resources
        val cal = Calendar.getInstance(tz, locale)
        cal.timeInMillis = month + NOON_MS
        title?.text = monthFormat.format(cal.time)
        val first = cal.firstDayOfWeek
        val week = Calendar.getInstance(tz, locale).apply {
            timeInMillis = month + NOON_MS
            set(Calendar.DAY_OF_WEEK, first)
        }
        for (t in weekdays) {
            t.text = weekdayFormat.format(week.time)
            week.add(Calendar.DAY_OF_MONTH, 1)
        }
        val lead = (cal.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
        val last = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        for ((i, cell) in cells.withIndex()) {
            val n = i - lead + 1
            if (n < 1 || n > last) {
                cell.clear()
                continue
            }
            cal.set(Calendar.DAY_OF_MONTH, n)
            val day = TimelineDays.dayStart(cal.timeInMillis, tz)
            val st = d.stats?.get(day)
            // Unknown without the day list: every day in range stays open, and only loaded ones are dotted.
            val footage = day in d.minDay..d.today &&
                (d.stats == null || (st?.footageMs ?: 0L) > 0L || day in d.footage)
            val dotted = footage && ((st?.footageMs ?: 0L) > 0L || day in d.footage)
            val alerts = if (footage) st?.alerts ?: 0 else 0
            cell.set(day, numberFormat.format(n.toLong()), footage, dotted, alerts > 0, day == d.selected, day == d.today)
            cell.contentDescription = describe(res, day, footage, st, alerts, day == d.today)
        }
        setArrow(prev, month > monthStartOf(d.minDay))
        setArrow(next, month < monthStartOf(d.today))
    }

    private fun describe(res: android.content.res.Resources, day: Long, footage: Boolean, st: TlDay?, alerts: Int, today: Boolean): String {
        val date = dateFormat.format(Date(day + NOON_MS))
        val parts = ArrayList<String>(4)
        if (today) parts.add(res.getString(R.string.timeline_today))
        if (!footage) {
            parts.add(res.getString(R.string.timeline_empty_day, date))
            return parts.joinToString(", ")
        }
        parts.add(date)
        val ms = st?.footageMs ?: 0L
        if (ms > 0L) parts.add(res.getString(R.string.timeline_day_cd_footage, GapReasons.durationText(res, ms)))
        if (alerts > 0) parts.add(res.getQuantityString(R.plurals.timeline_day_cd_alerts, alerts, alerts))
        return parts.joinToString(", ")
    }

    private fun setArrow(v: View?, enabled: Boolean) {
        v ?: return
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun stepMonth(by: Int) {
        val cal = Calendar.getInstance(tz, locale)
        cal.timeInMillis = month + NOON_MS
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.MONTH, by)
        month = clampMonth(TimelineDays.dayStart(cal.timeInMillis, tz))
        render()
    }

    private fun clampMonth(m: Long): Long {
        val d = data ?: return m
        val lo = monthStartOf(d.minDay)
        val hi = monthStartOf(d.today)
        return monthStartOf(m).coerceIn(minOf(lo, hi), hi)
    }

    private fun monthStartOf(t: Long): Long {
        val cal = Calendar.getInstance(tz, locale)
        cal.timeInMillis = TimelineDays.dayStart(t, tz) + NOON_MS
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return TimelineDays.dayStart(cal.timeInMillis, tz)
    }

    private fun legendDot(density: Float, color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        val s = (8 * density).toInt()
        setSize(s, s)
    }

    private companion object {
        const val GRID_ROWS = 6
        const val CELL_HEIGHT_DP = 56
        const val DIALOG_WIDTH_DP = 7 * 64 + 2 * 16 + 2 * 24
        const val NOON_MS = 12L * 3_600_000L
        const val DISABLED_ALPHA = 0.38f
    }
}

/** One day of the picker: its number, a dot when it has footage (amber with alerts), filled when shown, ringed today. */
internal class TlDayCell(context: Context) : View(context) {
    var day = -1L
        private set
    private var label = ""
    private var dot = false
    private var alert = false
    private var current = false
    private var today = false

    private val density = resources.displayMetrics.density
    private val cInk = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
    private val cMuted = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val cSelected = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.DKGRAY)
    private val cOnSelected = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
    private val cAlert = alertColor(context)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        fontFeatureSettings = "tnum"
        // sp, capped at 1.3x as in the strip: the cell is a fixed 56 dp.
        textSize = minOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics), 16f * density * 1.3f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    init {
        isClickable = true
        isFocusable = true
        background = ripple()
    }

    fun set(day: Long, label: String, enabled: Boolean, dot: Boolean, alert: Boolean, current: Boolean, today: Boolean) {
        this.day = day
        this.label = label
        this.dot = dot
        this.alert = alert
        this.current = current
        this.today = today
        isEnabled = enabled
        isSelected = current
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        invalidate()
    }

    fun clear() {
        day = -1L
        label = ""
        dot = false
        current = false
        today = false
        isEnabled = false
        isSelected = false
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (label.isEmpty()) return
        val cx = width * 0.5f
        val cy = height * 0.5f
        val r = minOf(minOf(width, height) * 0.5f - 2f * density, 24f * density)
        if (current) {
            fill.color = cSelected
            canvas.drawCircle(cx, cy, r, fill)
        } else if (today) {
            ring.color = cMuted
            canvas.drawCircle(cx, cy, r - ring.strokeWidth, ring)
        }
        textPaint.color = when {
            current -> cOnSelected
            isEnabled -> cInk
            else -> (cInk and 0x00FFFFFF) or (0x61 shl 24)
        }
        canvas.drawText(label, cx, cy - 3f * density + textPaint.textSize * 0.355f, textPaint)
        if (dot) {
            fill.color = when {
                current -> cOnSelected
                alert -> cAlert
                else -> cMuted
            }
            canvas.drawCircle(cx, cy + 12f * density, 3f * density, fill)
        }
    }

    // A round press highlight the size of the selection disc.
    private fun ripple(): RippleDrawable {
        val highlight = MaterialColors.getColor(this, android.R.attr.colorControlHighlight, 0x1F000000)
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val mask = LayerDrawable(arrayOf<Drawable>(disc)).apply {
            val s = (48 * density).toInt()
            setLayerSize(0, s, s)
            setLayerGravity(0, Gravity.CENTER)
        }
        return RippleDrawable(ColorStateList.valueOf(highlight), null, mask)
    }

    companion object {
        fun alertColor(c: Context): Int = ContextCompat.getColor(c, R.color.overdrive_status_warning)
    }
}
