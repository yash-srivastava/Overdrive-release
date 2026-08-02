package com.overdrive.app.ui.fragment

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.overdrive.app.R
import com.overdrive.app.ui.view.EventTimelineView
import com.overdrive.app.ui.view.ZoomableVideoView
import org.json.JSONObject
import java.io.File

/**
 * Native video player with event timeline overlay.
 * Built on a [ZoomableVideoView] so the same MP4 (a 2x2 mosaic of the four
 * AVM cameras) can be panned/zoomed to a single quadrant for fullscreen
 * single-camera review without a re-encode.
 * Reads JSON sidecar for detection event markers on the timeline.
 *
 * Optional playlist support: callers can pass [ARG_PLAYLIST_PATHS] +
 * [ARG_PLAYLIST_TITLES] arrays plus a starting [ARG_PLAYLIST_INDEX] to enable
 * the prev/next buttons. When the playlist has 2+ entries, both buttons
 * appear in the bottom transport row and load the adjacent clip in-place
 * (no fragment recreate, no nav transition).
 */
class VideoPlayerFragment : Fragment() {

    companion object {
        const val ARG_VIDEO_PATH = "video_path"
        const val ARG_VIDEO_TITLE = "video_title"
        /**
         * When true, the player is hosted as a child fragment inside another
         * surface (e.g. the Recordings landscape preview pane). In inline mode:
         *   - The back button is hidden (the parent owns navigation).
         *   - No findNavController().popBackStack() / navigateUp() calls are
         *     made — those would unwind the parent's nav stack and break the
         *     host. Errors silently no-op instead of popping.
         */
        const val ARG_INLINE = "inline"
        /** Use reduced chrome when the host renders the title and details. */
        const val ARG_COMPACT_INLINE = "compact_inline"
        /** Open on frame zero with visible controls instead of autoplaying. */
        const val ARG_START_PAUSED = "start_paused"
        /** Optional: parallel arrays defining a playlist for prev/next. */
        const val ARG_PLAYLIST_PATHS = "playlist_paths"
        const val ARG_PLAYLIST_TITLES = "playlist_titles"
        const val ARG_PLAYLIST_INDEX = "playlist_index"
        private const val SEEK_UPDATE_MS = 250L
        // SharedPreferences-backed persistence for the mute toggle.
        private const val PREFS_PLAYER = "video_player_prefs"
        private const val PREF_MUTED = "muted"
        // Persisted quadrant zoom selection. Survives clip changes so a user
        // who's been reviewing the rear camera in clip A keeps that zoom on
        // clip B without re-tapping. Value is the [ZoomableVideoView.Quadrant]
        // enum name; missing/unknown → ALL.
        private const val PREF_QUADRANT = "quadrant"
        // saved-state keys for surviving config changes (rotation, dark
        // mode toggle). Playhead + playing-state get restored so the user
        // doesn't lose their place across orientation changes.
        private const val STATE_POSITION_MS = "videoPlayer.positionMs"
        private const val STATE_WAS_PLAYING = "videoPlayer.wasPlaying"
    }

    private var inlineMode: Boolean = false
    private var compactInlineMode: Boolean = false
    private var playlistPaths: Array<String> = emptyArray()
    private var playlistTitles: Array<String> = emptyArray()
    private var playlistIndex: Int = -1

    private lateinit var videoView: ZoomableVideoView
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvEventInfo: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnBack: ImageButton
    private var btnPrev: ImageButton? = null
    private var btnNext: ImageButton? = null
    private var btnFullscreen: ImageButton? = null
    private var btnMute: ImageButton? = null

    // Quadrant selector — null on layouts without the bar (none today, but
    // the host could legitimately strip it for an embedded preview surface).
    private var quadrantBar: View? = null
    private var btnQuadrantAll: ImageButton? = null
    private var btnQuadrantFront: ImageButton? = null
    private var btnQuadrantRight: ImageButton? = null
    private var btnQuadrantRear: ImageButton? = null
    private var btnQuadrantLeft: ImageButton? = null
    private var currentQuadrant: ZoomableVideoView.Quadrant = ZoomableVideoView.Quadrant.ALL
    // Tracks the desired mute state across clip changes within this
    // fragment instance. Initial value reads from SharedPreferences (default
    // = muted) so the user's choice survives navigation. The MediaPlayer's
    // own muted state is set in setOnPreparedListener once playback starts.
    private var userPrefMuted: Boolean = true
    // Held while the player is prepared; lets us flip mute on demand from
    // the button click handler without waiting for the next prepare cycle.
    private var currentMediaPlayer: MediaPlayer? = null
    private lateinit var eventTimeline: EventTimelineView

    /**
     * Host-supplied callback driving the inline maximize affordance. The
     * fragment owns nothing about layout — it just flips its own icon and
     * tells the host "user wants fullscreen = true/false". The host then
     * animates the surrounding chrome and stretches the preview pane.
     *
     * Null => hide the button entirely (e.g. when the player isn't inline,
     * or the host doesn't want to support fullscreen). Set this BEFORE the
     * fragment's view is created if possible; updating it afterwards calls
     * [refreshFullscreenButton] to push the new visibility.
     */
    var onFullscreenToggle: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            // View may not be inflated yet during host setup — guard.
            if (view != null) refreshFullscreenButton()
        }

    /** Notifies an inline host when prev/next or auto-advance changes clips. */
    var onPlaylistItemChanged: ((String) -> Unit)? = null

    /**
     * Current maximize state. The host is the source of truth for the
     * actual layout; this just mirrors what was last requested so the icon
     * + content description stay in sync. Setting it externally (e.g. when
     * the host force-collapses on back press) updates the icon without
     * invoking [onFullscreenToggle] again.
     */
    var isFullscreen: Boolean = false
        set(value) {
            field = value
            refreshFullscreenButton()
            refreshInlineChrome()
        }

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false

    // Auto-hide overlay controls
    private var topBar: View? = null
    private var bottomControls: View? = null
    private var overlayVisible = true
    private val OVERLAY_HIDE_DELAY = 3000L

    private val hideOverlayRunnable = Runnable {
        if (videoView.isPlaying) {
            setOverlayVisible(false)
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isUserSeeking && videoView.isPlaying) {
                val pos = videoView.currentPosition
                seekBar.progress = pos
                tvCurrentTime.text = formatTime(pos)
                eventTimeline.setPlayhead(pos.toLong())
            }
            handler.postDelayed(this, SEEK_UPDATE_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_video_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        videoView = view.findViewById(R.id.videoView)
        seekBar = view.findViewById(R.id.seekBar)
        tvCurrentTime = view.findViewById(R.id.tvCurrentTime)
        tvDuration = view.findViewById(R.id.tvDuration)
        tvTitle = view.findViewById(R.id.tvTitle)
        tvMeta = view.findViewById(R.id.tvMeta)
        tvEventInfo = view.findViewById(R.id.tvEventInfo)
        btnPlayPause = view.findViewById(R.id.btnPlayPause)
        btnBack = view.findViewById(R.id.btnBack)
        btnPrev = view.findViewById(R.id.btnPrev)
        btnNext = view.findViewById(R.id.btnNext)
        btnFullscreen = view.findViewById(R.id.btnFullscreen)
        btnMute = view.findViewById(R.id.btnMute)
        quadrantBar = view.findViewById(R.id.quadrantBar)
        btnQuadrantAll = view.findViewById(R.id.btnQuadrantAll)
        btnQuadrantFront = view.findViewById(R.id.btnQuadrantFront)
        btnQuadrantRight = view.findViewById(R.id.btnQuadrantRight)
        btnQuadrantRear = view.findViewById(R.id.btnQuadrantRear)
        btnQuadrantLeft = view.findViewById(R.id.btnQuadrantLeft)
        // Restore last-chosen mute state so the user's preference carries
        // across clips and app restarts. Order of precedence (mirrors the
        // events.html web player):
        //   1. Explicit pref written by a previous mute-button tap wins.
        //   2. No explicit choice yet AND the user has audio recording
        //      enabled in unified config → default to UNMUTED (so they can
        //      actually hear the captured audio without a second tap).
        //   3. Otherwise default to muted (safer first-run experience).
        val prefs = requireContext()
            .getSharedPreferences(PREFS_PLAYER, Context.MODE_PRIVATE)
        userPrefMuted = if (prefs.contains(PREF_MUTED)) {
            prefs.getBoolean(PREF_MUTED, true)
        } else {
            !readAudioEnabledFromConfig()
        }
        refreshMuteIcon()

        // Restore persisted quadrant. ALL is the safe default for both new
        // installs and clips that pre-date this feature. The enum's name()
        // matches the persisted value exactly so a future enum-rename will
        // surface as a missing-key fallback to ALL — never a crash.
        //
        // Pre-layout setQuadrant updates the field but skips the matrix
        // paint (width/height==0). [ZoomableVideoView.onSizeChanged]
        // picks up the stored quadrant once layout completes and paints
        // the matching matrix on the first frame, so no flash.
        currentQuadrant = runCatching {
            val raw = prefs.getString(PREF_QUADRANT, ZoomableVideoView.Quadrant.ALL.name)
            ZoomableVideoView.Quadrant.valueOf(raw ?: ZoomableVideoView.Quadrant.ALL.name)
        }.getOrDefault(ZoomableVideoView.Quadrant.ALL)
        videoView.setQuadrant(currentQuadrant, animate = false)
        refreshQuadrantSelection()

        eventTimeline = view.findViewById(R.id.eventTimeline)
        topBar = view.findViewById(R.id.topBar)
        bottomControls = view.findViewById(R.id.bottomControls)

        inlineMode = arguments?.getBoolean(ARG_INLINE, false) ?: false
        compactInlineMode = arguments?.getBoolean(ARG_COMPACT_INLINE, false) ?: false

        // Optional playlist for prev/next.
        playlistPaths = arguments?.getStringArray(ARG_PLAYLIST_PATHS) ?: emptyArray()
        playlistTitles = arguments?.getStringArray(ARG_PLAYLIST_TITLES) ?: emptyArray()
        playlistIndex = arguments?.getInt(ARG_PLAYLIST_INDEX, -1) ?: -1

        val initialPath = arguments?.getString(ARG_VIDEO_PATH) ?: run {
            // In inline mode we must NOT pop the parent's nav stack — just
            // bail out quietly (the host can decide what to render instead).
            if (!inlineMode) findNavController().popBackStack()
            return
        }
        // If the initial path is in the playlist, sync the index.
        if (playlistIndex < 0 && playlistPaths.isNotEmpty()) {
            playlistIndex = playlistPaths.indexOf(initialPath)
        }
        val initialTitle = arguments?.getString(ARG_VIDEO_TITLE) ?: File(initialPath).name

        // In inline mode the parent screen owns navigation, so suppress our
        // own back affordance.
        if (inlineMode) {
            btnBack.visibility = View.GONE
        }
        refreshInlineChrome()

        // Read playhead + playing intent stashed across config changes
        // (rotation, dark mode, etc). Don't apply yet — loadVideo calls
        // setVideoURI which resets the resume policy. Apply right after
        // the URI is set, while the prepare is still in flight.
        val restorePositionMs = savedInstanceState?.getInt(STATE_POSITION_MS, 0) ?: 0
        val startPaused = arguments?.getBoolean(ARG_START_PAUSED, false) ?: false
        val restoreWasPlaying = savedInstanceState
            ?.getBoolean(STATE_WAS_PLAYING, !startPaused)
            ?: !startPaused

        loadVideo(initialPath, initialTitle)

        if (restorePositionMs > 0 || !restoreWasPlaying) {
            videoView.primeResume(restorePositionMs, restoreWasPlaying)
        }

        setupControls()
        setupOverlayAutoHide()

        updatePrevNextVisibility()
        refreshFullscreenButton()
    }

    private fun loadVideo(path: String, title: String) {
        tvTitle.text = title
        // File size meta
        val file = File(path)
        if (file.exists()) {
            tvMeta.text = formatSize(file.length())
        } else {
            tvMeta.text = ""
        }

        setupVideoPlayer(path)
        loadEventTimeline(path)
    }

    /**
     * Sync the maximize button's visibility, icon, and content description
     * to the current host capability ([onFullscreenToggle]) and state
     * ([isFullscreen]). Cheap to call; safe before the view is bound (no-op).
     */
    private fun refreshFullscreenButton() {
        val btn = btnFullscreen ?: return
        if (onFullscreenToggle == null) {
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        if (isFullscreen) {
            btn.setImageResource(R.drawable.ic_fullscreen_exit)
            btn.contentDescription = getString(R.string.cd_player_minimize)
        } else {
            btn.setImageResource(R.drawable.ic_fullscreen)
            btn.contentDescription = getString(R.string.cd_player_maximize)
        }
    }

    /**
     * The landscape host already provides a headline, filename, severity and
     * metadata around the player. Keep the embedded overlay compact so those
     * labels are not duplicated. Fullscreen restores the complete player
     * chrome because the host context is hidden in that state.
     */
    private fun refreshInlineChrome() {
        if (!::tvTitle.isInitialized) return
        val showFullChrome = !compactInlineMode || isFullscreen
        tvTitle.visibility = if (showFullChrome) View.VISIBLE else View.GONE
        tvMeta.visibility = if (showFullChrome) View.VISIBLE else View.GONE
        quadrantBar?.visibility = if (showFullChrome) View.VISIBLE else View.GONE
    }

    private fun updatePrevNextVisibility() {
        val show = playlistPaths.size >= 2 && playlistIndex >= 0
        btnPrev?.visibility = if (show) View.VISIBLE else View.GONE
        btnNext?.visibility = if (show) View.VISIBLE else View.GONE
        btnPrev?.isEnabled = show && playlistIndex > 0
        btnPrev?.alpha = if (btnPrev?.isEnabled == true) 1f else 0.4f
        btnNext?.isEnabled = show && playlistIndex >= 0 && playlistIndex < playlistPaths.size - 1
        btnNext?.alpha = if (btnNext?.isEnabled == true) 1f else 0.4f
    }

    private fun jumpTo(newIndex: Int) {
        if (newIndex < 0 || newIndex >= playlistPaths.size) return
        playlistIndex = newIndex
        // Tear down current playback before loading the next URI to avoid
        // a brief frame of the previous clip flashing behind the new one.
        handler.removeCallbacks(updateRunnable)
        videoView.stopPlayback()
        seekBar.progress = 0
        tvCurrentTime.text = getString(R.string.player_time_zero)

        val path = playlistPaths[newIndex]
        val title = playlistTitles.getOrNull(newIndex) ?: File(path).name
        loadVideo(path, title)
        // Keep the host's index in sync so its onPlay-from-list also tracks.
        // Update arguments so config-change (rotation) restores the right
        // entry rather than the original starting one.
        arguments?.apply {
            putString(ARG_VIDEO_PATH, path)
            putString(ARG_VIDEO_TITLE, title)
            putInt(ARG_PLAYLIST_INDEX, newIndex)
        }
        onPlaylistItemChanged?.invoke(path)
        updatePrevNextVisibility()
        // Make sure the controls are visible during a transition so the user
        // can chain another prev/next quickly.
        setOverlayVisible(true)
        scheduleOverlayHide()
    }

    /**
     * Switch to [target] and persist the selection so the next clip /
     * relaunch honors it. The transform animation is owned by the
     * ZoomableVideoView itself; this method just drives the icon highlight
     * + persistence + auto-hide reset.
     *
     * Tap-to-toggle: tapping the active quadrant returns to ALL. Mirrors
     * the events.html behavior so the surfaces feel identical.
     */
    private fun selectQuadrant(target: ZoomableVideoView.Quadrant) {
        val resolved = if (target == currentQuadrant && target != ZoomableVideoView.Quadrant.ALL) {
            ZoomableVideoView.Quadrant.ALL
        } else target
        if (resolved == currentQuadrant) return
        currentQuadrant = resolved
        videoView.setQuadrant(resolved, animate = true)
        refreshQuadrantSelection()
        runCatching {
            requireContext()
                .getSharedPreferences(PREFS_PLAYER, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_QUADRANT, resolved.name)
                .apply()
        }
        if (overlayVisible && videoView.isPlaying) scheduleOverlayHide()
    }

    /**
     * Repaint the quadrant icon row so the active button is fully opaque
     * and the rest are dimmed. Cheaper than swapping background drawables
     * and avoids inconsistencies with our drawable selectors.
     */
    private fun refreshQuadrantSelection() {
        val map = listOf(
            btnQuadrantAll to ZoomableVideoView.Quadrant.ALL,
            btnQuadrantFront to ZoomableVideoView.Quadrant.FRONT,
            btnQuadrantRight to ZoomableVideoView.Quadrant.RIGHT,
            btnQuadrantRear to ZoomableVideoView.Quadrant.REAR,
            btnQuadrantLeft to ZoomableVideoView.Quadrant.LEFT
        )
        for ((btn, q) in map) {
            btn ?: continue
            btn.alpha = if (q == currentQuadrant) 1f else 0.55f
            btn.isSelected = q == currentQuadrant
        }
    }

    /**
     * Swap the quadrant-selector glyphs to match the clip's composition
     * layout. STANDARD clips use the 2x2 mosaic-map icons; DASHCAM clips use
     * the dashcam-map icons (full-width front band over three lower thirds) so
     * each button visually maps to where its camera actually sits in the
     * frame. The zoom REGIONS are already layout-aware (see
     * ZoomableVideoView.regionFor); this keeps the icons honest about which
     * camera each button selects. Called wherever videoView.setLayout() runs.
     */
    private fun applyQuadrantLayoutIcons(layout: ZoomableVideoView.Layout) {
        val dashcam = layout == ZoomableVideoView.Layout.DASHCAM
        btnQuadrantAll?.setImageResource(
            if (dashcam) R.drawable.ic_dashcam_all else R.drawable.ic_quadrant_all)
        btnQuadrantFront?.setImageResource(
            if (dashcam) R.drawable.ic_dashcam_front else R.drawable.ic_quadrant_front)
        btnQuadrantRight?.setImageResource(
            if (dashcam) R.drawable.ic_dashcam_right else R.drawable.ic_quadrant_right)
        btnQuadrantRear?.setImageResource(
            if (dashcam) R.drawable.ic_dashcam_rear else R.drawable.ic_quadrant_rear)
        btnQuadrantLeft?.setImageResource(
            if (dashcam) R.drawable.ic_dashcam_left else R.drawable.ic_quadrant_left)
    }

    /**
     * Read the recording.audioEnabled flag from UnifiedConfigManager so the
     * mute default for first-ever playback can mirror whether the user has
     * audio recording on. Best-effort: any exception or missing section
     * yields false (= keep the muted default).
     */
    private fun readAudioEnabledFromConfig(): Boolean {
        return try {
            val recCfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                .optJSONObject("recording")
            recCfg?.optBoolean("audioEnabled", false) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Apply the current userPrefMuted state to a MediaPlayer. The public
     * API only exposes setVolume(left, right) — there's no setMuted — so
     * we volume-zero. Audio comes back to full when toggled off; we don't
     * preserve a partial volume because the player UI has no separate
     * volume slider, just on/off.
     */
    private fun applyMuteToPlayer(mp: MediaPlayer) {
        try {
            if (userPrefMuted) {
                mp.setVolume(0f, 0f)
            } else {
                mp.setVolume(1f, 1f)
            }
        } catch (e: IllegalStateException) {
            // MediaPlayer in an invalid state (rare race during teardown);
            // the next setup cycle will pick up the preference.
        }
    }

    /**
     * Repaint the mute button to reflect userPrefMuted. Called from
     * onViewCreated after restoring the pref and from the click handler.
     */
    private fun refreshMuteIcon() {
        val btn = btnMute ?: return
        if (userPrefMuted) {
            btn.setImageResource(R.drawable.ic_volume_off)
        } else {
            btn.setImageResource(R.drawable.ic_volume_on)
        }
    }

    private fun setupVideoPlayer(path: String) {
        // Drop the stale MediaPlayer reference BEFORE setVideoURI tears down
        // the previous one. ZoomableVideoView releases the old player
        // internally on setVideoURI; without this clear, jumpTo() leaves
        // currentMediaPlayer pointing at a released instance until the new
        // prepare callback fires, which would route mute toggles to a dead
        // player.
        currentMediaPlayer = null
        videoView.setVideoURI(Uri.fromFile(File(path)))

        videoView.setOnPreparedListener { mp ->
            val duration = videoView.duration
            seekBar.max = duration
            tvDuration.text = formatTime(duration)
            eventTimeline.setPlayhead(0)

            mp.isLooping = false
            currentMediaPlayer = mp
            // Apply user's persisted mute preference. setVolume(0,0) is the
            // canonical way to mute a MediaPlayer — there's no setMuted on
            // the public API. Done before start() so the first frame's
            // audio is silent if the user wants it that way.
            applyMuteToPlayer(mp)
            // Honor the user's pre-background play state. shouldAutoResume()
            // returns true on first-clip prepare (default) and on returns
            // where the user was playing before backgrounding; false when
            // they had explicitly paused. Without this gate every resume
            // unconditionally restarted playback, silently overriding
            // intentional pauses.
            if (videoView.shouldAutoResume()) {
                videoView.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                handler.post(updateRunnable)
                scheduleOverlayHide()
            } else {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                setOverlayVisible(true)
            }
        }

        videoView.setOnCompletionListener {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            handler.removeCallbacks(updateRunnable)
            handler.removeCallbacks(hideOverlayRunnable)
            setOverlayVisible(true)
            // Drop the now-finished MediaPlayer so a stray mute-toggle tap
            // between completion and the next prepare doesn't reach into a
            // released instance. The new prepared listener resets it.
            currentMediaPlayer = null
            // Auto-advance to the next clip so the user can review a run of
            // clips without tapping between each. Callers build the playlist in
            // chronological order (oldest->newest), so index+1 is the NEWER
            // clip even though the library lists newest-first.
            val nextIdx = playlistIndex + 1
            if (playlistPaths.isNotEmpty() && nextIdx in playlistPaths.indices) {
                jumpTo(nextIdx)
            }
        }

        videoView.setOnErrorListener { _, what, extra ->
            android.util.Log.e("VideoPlayer", "Error: what=$what extra=$extra")
            tvEventInfo.text = getString(R.string.video_player_playback_error)
            true
        }
    }

    private fun setupControls() {
        btnBack.setOnClickListener {
            if (!inlineMode) findNavController().popBackStack()
        }

        btnPlayPause.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                handler.removeCallbacks(hideOverlayRunnable)
                handler.removeCallbacks(updateRunnable)
            } else {
                videoView.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                handler.removeCallbacks(updateRunnable)
                handler.post(updateRunnable)
                scheduleOverlayHide()
            }
        }

        btnPrev?.setOnClickListener {
            if (playlistIndex > 0) jumpTo(playlistIndex - 1)
        }
        btnNext?.setOnClickListener {
            if (playlistIndex >= 0 && playlistIndex < playlistPaths.size - 1) {
                jumpTo(playlistIndex + 1)
            }
        }

        btnQuadrantAll?.setOnClickListener { selectQuadrant(ZoomableVideoView.Quadrant.ALL) }
        btnQuadrantFront?.setOnClickListener { selectQuadrant(ZoomableVideoView.Quadrant.FRONT) }
        btnQuadrantRight?.setOnClickListener { selectQuadrant(ZoomableVideoView.Quadrant.RIGHT) }
        btnQuadrantRear?.setOnClickListener { selectQuadrant(ZoomableVideoView.Quadrant.REAR) }
        btnQuadrantLeft?.setOnClickListener { selectQuadrant(ZoomableVideoView.Quadrant.LEFT) }

        btnMute?.setOnClickListener {
            userPrefMuted = !userPrefMuted
            // Persist immediately so a crash / process death between now and
            // onPause doesn't lose the user's choice.
            requireContext()
                .getSharedPreferences(PREFS_PLAYER, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_MUTED, userPrefMuted)
                .apply()
            currentMediaPlayer?.let { applyMuteToPlayer(it) }
            refreshMuteIcon()
            // Reset auto-hide so the user sees the icon flip before the
            // chrome fades.
            if (overlayVisible && videoView.isPlaying) scheduleOverlayHide()
        }

        btnFullscreen?.setOnClickListener {
            // Optimistically flip the icon — the host's animated swap takes
            // ~250ms, and waiting for the round-trip would feel laggy. If the
            // host rejects (e.g. back press while transitioning), it'll
            // re-set isFullscreen which triggers refreshFullscreenButton().
            val target = !isFullscreen
            isFullscreen = target
            onFullscreenToggle?.invoke(target)
            // Reset the auto-hide timer so the user can take a second look at
            // the now-expanded view before the chrome fades.
            if (overlayVisible && videoView.isPlaying) scheduleOverlayHide()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress)
                    eventTimeline.setPlayhead(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                videoView.seekTo(sb?.progress ?: 0)
            }
        })

        eventTimeline.setOnClickListener { _ ->
            if (videoView.duration > 0) {
                // Not ideal for precise seeking but works for tap-to-seek
            }
        }
    }

    private fun setupOverlayAutoHide() {
        // Single tap on the video = toggle the chrome (overlay auto-hide).
        // The TextureView's GestureDetector dispatches single-tap-confirmed
        // -> performClick(), so a double-tap doesn't accidentally also
        // toggle the chrome between the two taps.
        videoView.setOnClickListener {
            if (overlayVisible) {
                setOverlayVisible(false)
            } else {
                setOverlayVisible(true)
                scheduleOverlayHide()
            }
        }
        // Double-tap = zoom into the tapped quadrant (or reset to ALL when
        // already zoomed). Mirrors the events.html web player behavior.
        videoView.setOnDoubleTapListener { target ->
            selectQuadrant(target)
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
        val duration = 250L
        // Every chrome surface fades together — top bar, quadrant selector,
        // bottom controls. quadrantBar joining this group is intentional:
        // the user shouldn't see "switch camera" handles floating over a
        // playing-but-controls-hidden video.
        val activeQuadrantBar = quadrantBar?.takeIf { !compactInlineMode || isFullscreen }
        if (activeQuadrantBar == null) quadrantBar?.visibility = View.GONE
        val chrome = listOfNotNull(topBar, activeQuadrantBar, bottomControls)
        if (visible) {
            for (v in chrome) {
                v.animate().cancel()
                v.visibility = View.VISIBLE
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(duration).start()
            }
        } else {
            for (v in chrome) {
                v.animate().cancel()
                v.animate().alpha(0f).setDuration(duration)
                    .withEndAction { v.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun scheduleOverlayHide() {
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY)
    }

    /**
     * Load the JSON sidecar (event_YYYYMMDD_HHMMSS.json) for timeline markers.
     */
    private fun loadEventTimeline(videoPath: String) {
        Thread {
            try {
                val jsonPath = videoPath.replace(".mp4", ".json")
                val jsonFile = File(jsonPath)
                if (!jsonFile.exists()) {
                    activity?.runOnUiThread {
                        // No sidecar → standard 2x2 composition. Resetting
                        // here (not just defaulting) matters for playlists:
                        // a dashcam clip followed by a sidecar-less standard
                        // clip must drop back to the 2x2 zoom regions.
                        videoView.setLayout(ZoomableVideoView.Layout.STANDARD)
                        applyQuadrantLayoutIcons(ZoomableVideoView.Layout.STANDARD)
                        eventTimeline.setEvents(emptyList(), 0L)
                        tvEventInfo.text = getString(R.string.video_player_no_events)
                    }
                    return@Thread
                }

                val json = JSONObject(jsonFile.readText())
                // Composition layout drives the per-camera zoom regions. A
                // dashcam clip's sidecar may carry ONLY this field (no events),
                // so read + apply it BEFORE the events null-check below.
                val clipLayout = if ("dashcam" == json.optString("layout", "standard"))
                    ZoomableVideoView.Layout.DASHCAM else ZoomableVideoView.Layout.STANDARD
                activity?.runOnUiThread {
                    videoView.setLayout(clipLayout)
                    applyQuadrantLayoutIcons(clipLayout)
                }
                val durationMs = json.optLong("durationMs", 0)
                val eventsArray = json.optJSONArray("events")
                if (eventsArray == null) {
                    activity?.runOnUiThread {
                        eventTimeline.setEvents(emptyList(), durationMs)
                        tvEventInfo.text = getString(R.string.video_player_no_events)
                    }
                    return@Thread
                }
                val stats = json.optJSONObject("stats")

                val events = mutableListOf<EventTimelineView.TimelineEvent>()
                for (i in 0 until eventsArray.length()) {
                    val ev = eventsArray.getJSONObject(i)
                    events.add(EventTimelineView.TimelineEvent(
                        startMs = ev.getLong("start"),
                        endMs = ev.getLong("end"),
                        type = ev.optString("type", "motion"),
                        confidence = ev.optDouble("maxConf", 0.0).toFloat()
                    ))
                }

                val legend = buildString {
                    if (stats != null) {
                        val p = stats.optInt("person", 0)
                        val c = stats.optInt("car", 0)
                        val b = stats.optInt("bike", 0)
                        val m = stats.optInt("motion", 0)
                        val parts = mutableListOf<String>()
                        if (p > 0) parts.add("$p person")
                        if (c > 0) parts.add("$c car")
                        if (b > 0) parts.add("$b bike")
                        if (m > 0) parts.add("$m motion")
                        append(parts.joinToString(" · "))
                    }
                }

                activity?.runOnUiThread {
                    eventTimeline.setEvents(events, durationMs)
                    tvEventInfo.text = if (legend.isNotEmpty()) legend else ""
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayer", "Timeline load failed: ${e.message}")
            }
        }.start()
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
        // Snapshot playing-state BEFORE pausing so the resume-after-
        // background path knows whether to auto-start on re-prepare. Order
        // matters: pause() flips isPlaying to false, so a snapshot taken
        // after would always read false.
        videoView.snapshotPlayingState()
        if (videoView.isPlaying) videoView.pause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persist playhead + playing intent across config changes.
        //
        // Read BEFORE any teardown — by the time onDestroyView runs, the
        // MediaPlayer has been released and currentPosition reads 0.
        //
        // The Android lifecycle invokes onPause() BEFORE onSaveInstanceState,
        // and our onPause unconditionally pauses the player. So a literal
        // `videoView.isPlaying` check here always reads false — the
        // user's pre-rotation playing intent would be silently dropped.
        // [shouldAutoResume] returns the value snapshotPlayingState()
        // captured in onPause BEFORE the pause() call, which is the
        // truthful pre-pause state.
        if (videoView.duration > 0) {
            outState.putInt(STATE_POSITION_MS, videoView.currentPosition)
            outState.putBoolean(STATE_WAS_PLAYING, videoView.shouldAutoResume())
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(hideOverlayRunnable)
        videoView.stopPlayback()
        super.onDestroyView()
    }
}
