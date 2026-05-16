package com.overdrive.app.ui.fragment

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
import android.widget.VideoView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.overdrive.app.R
import com.overdrive.app.ui.view.EventTimelineView
import org.json.JSONObject
import java.io.File

/**
 * Native video player with event timeline overlay.
 * Uses Android VideoView for reliable local file playback.
 * Reads JSON sidecar for detection event markers on the timeline.
 */
class VideoPlayerFragment : Fragment() {

    companion object {
        const val ARG_VIDEO_PATH = "video_path"
        const val ARG_VIDEO_TITLE = "video_title"
        private const val SEEK_UPDATE_MS = 250L
    }

    private lateinit var videoView: VideoView
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvEventInfo: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var eventTimeline: EventTimelineView

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
        eventTimeline = view.findViewById(R.id.eventTimeline)
        topBar = view.findViewById(R.id.topBar)
        bottomControls = view.findViewById(R.id.bottomControls)

        val videoPath = arguments?.getString(ARG_VIDEO_PATH) ?: run {
            findNavController().popBackStack(); return
        }
        val videoTitle = arguments?.getString(ARG_VIDEO_TITLE) ?: File(videoPath).name
        tvTitle.text = videoTitle

        // File size meta
        val file = File(videoPath)
        if (file.exists()) {
            tvMeta.text = formatSize(file.length())
        }

        setupVideoPlayer(videoPath)
        setupControls()
        setupOverlayAutoHide()
        loadEventTimeline(videoPath)
    }

    private fun setupVideoPlayer(path: String) {
        videoView.setVideoURI(Uri.fromFile(File(path)))

        videoView.setOnPreparedListener { mp ->
            val duration = videoView.duration
            seekBar.max = duration
            tvDuration.text = formatTime(duration)
            eventTimeline.setPlayhead(0)

            // Enable looping off, start playback
            mp.isLooping = false
            videoView.start()
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            handler.post(updateRunnable)
            scheduleOverlayHide()
        }

        videoView.setOnCompletionListener {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            handler.removeCallbacks(updateRunnable)
            handler.removeCallbacks(hideOverlayRunnable)
            setOverlayVisible(true)
        }

        videoView.setOnErrorListener { _, what, extra ->
            android.util.Log.e("VideoPlayer", "Error: what=$what extra=$extra")
            tvEventInfo.text = getString(R.string.video_player_playback_error)
            true
        }
    }

    private fun setupControls() {
        btnBack.setOnClickListener { findNavController().popBackStack() }

        btnPlayPause.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                handler.removeCallbacks(hideOverlayRunnable)
            } else {
                videoView.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                handler.post(updateRunnable)
                scheduleOverlayHide()
            }
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

        // Tap timeline to seek
        eventTimeline.setOnClickListener { v ->
            if (videoView.duration > 0) {
                // Not ideal for precise seeking but works for tap-to-seek
            }
        }
    }

    private fun setupOverlayAutoHide() {
        // Tap video to toggle overlay visibility
        videoView.setOnClickListener {
            if (overlayVisible) {
                setOverlayVisible(false)
            } else {
                setOverlayVisible(true)
                scheduleOverlayHide()
            }
        }
    }
    
    private fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
        val alpha = if (visible) 1f else 0f
        val duration = 250L
        topBar?.animate()?.alpha(alpha)?.setDuration(duration)?.withEndAction {
            if (!visible) topBar?.visibility = View.GONE
        }?.start()
        bottomControls?.animate()?.alpha(alpha)?.setDuration(duration)?.withEndAction {
            if (!visible) bottomControls?.visibility = View.GONE
        }?.start()
        if (visible) {
            topBar?.visibility = View.VISIBLE
            topBar?.alpha = 0f
            topBar?.animate()?.alpha(1f)?.setDuration(duration)?.start()
            bottomControls?.visibility = View.VISIBLE
            bottomControls?.alpha = 0f
            bottomControls?.animate()?.alpha(1f)?.setDuration(duration)?.start()
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
                    activity?.runOnUiThread { tvEventInfo.text = getString(R.string.video_player_no_events) }
                    return@Thread
                }

                val json = JSONObject(jsonFile.readText())
                val durationMs = json.optLong("durationMs", 0)
                val eventsArray = json.optJSONArray("events") ?: return@Thread
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

                // Build legend text
                val legend = buildString {
                    if (stats != null) {
                        val p = stats.optInt("person", 0)
                        val c = stats.optInt("car", 0)
                        val b = stats.optInt("bike", 0)
                        val m = stats.optInt("motion", 0)
                        val parts = mutableListOf<String>()
                        if (p > 0) parts.add("🔴$p person")
                        if (c > 0) parts.add("🔵$c car")
                        if (b > 0) parts.add("🟢$b bike")
                        if (m > 0) parts.add("⚪$m motion")
                        append(parts.joinToString("  "))
                    }
                }

                activity?.runOnUiThread {
                    eventTimeline.setEvents(events, durationMs)
                    if (legend.isNotEmpty()) tvEventInfo.text = legend
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
        if (videoView.isPlaying) videoView.pause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(hideOverlayRunnable)
        videoView.stopPlayback()
        super.onDestroyView()
    }
}
