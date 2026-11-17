package com.overdrive.app.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.PathInterpolator
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * SOTA single-camera zoom over the AVM recording mosaic.
 *
 * The recordings are written by [com.overdrive.app.surveillance.GpuMosaicRecorder]
 * as a single MP4. Two compositions exist (see [Layout]):
 *
 *   STANDARD — a 2x2 grid of the four AVM cameras:
 *       Front (TL) | Right (TR)
 *       -----------+-----------
 *        Rear (BL) | Left  (BR)
 *
 *   DASHCAM — the front camera across the top band, with the
 *       left / rear / right cameras tiled across the bottom band in thirds.
 *
 * There is no per-camera stream on disk. To play "just the front camera in
 * fullscreen" we keep the same MP4 and apply a TextureView matrix that fits
 * the chosen camera's sub-rectangle of the frame to the view — the user
 * sees a single camera at native source resolution (1280×960 Seal / 1280×720
 * Tang) with zero re-encode, full audio, working scrub. Which sub-rectangle a
 * [Quadrant] denotes depends on the clip's [Layout].
 *
 * Why TextureView (not VideoView): VideoView wraps SurfaceView whose
 * compositor-blitted surface ignores View transforms. TextureView routes
 * the producer through the SurfaceTexture path so [setTransform] applies
 * to every frame. Trade-off: TextureView burns a few MB more GPU memory
 * than SurfaceView (one extra texture ping-pong). At 2560×1920 @ 30 fps
 * this is negligible compared to the encoder side of the device.
 *
 * Lifecycle: this view owns a [MediaPlayer]. The player is created lazily
 * once the SurfaceTexture is available AND a URI has been set; calling
 * [setVideoURI] before the surface is ready stashes the URI and re-runs
 * setup when [onSurfaceTextureAvailable] fires. The player is fully
 * released in [stopPlayback] / on detach.
 */
class ZoomableVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    /**
     * Note on casing: enum values are UPPERCASE for Kotlin idiom and
     * SharedPreferences storage; the web counterpart in events.js uses
     * lowercase ('all', 'front', ...) for CSS class compatibility. The
     * two surfaces don't share storage, so the casing split is a local
     * convention only — if we ever cross-correlate (analytics events,
     * shared logs), normalize at the boundary.
     */
    enum class Quadrant {
        ALL,        // No zoom — full frame
        FRONT,      // STANDARD: top-left   · DASHCAM: top band (full width)
        RIGHT,      // STANDARD: top-right  · DASHCAM: bottom-right third
        REAR,       // STANDARD: bottom-left· DASHCAM: bottom-middle third
        LEFT        // STANDARD: bottom-right·DASHCAM: bottom-left third
    }

    /**
     * Composition of the recorded MP4 — determines where each camera lives in
     * the frame, and therefore which sub-rectangle a [Quadrant] maps to. Must
     * mirror GpuMosaicRecorder's shader:
     *   STANDARD — 2x2 grid (Front TL, Right TR, Rear BL, Left BR).
     *   DASHCAM  — front across the top [DASHCAM_SPLIT] band; left/rear/right
     *              tiled across the bottom band in thirds.
     * Set per clip from the recording's sidecar `layout` tag; defaults to
     * STANDARD for legacy clips that pre-date the tag.
     */
    enum class Layout {
        STANDARD,
        DASHCAM
    }

    private var mediaPlayer: MediaPlayer? = null
    // Strong ref to the producer Surface so the finalizer doesn't collect
    // it while MediaPlayer still has a soft reference. On the BYD
    // head-unit (Android 10 / DiLink) the platform's aggressive GC
    // reaps an inline-constructed Surface mid-prepare — visible as
    // "A resource failed to call release" in logcat — which breaks the
    // producer queue and leaves playback frozen with audio running.
    // Holding it here keeps it alive for the full player lifetime;
    // releasePlayer() releases it explicitly.
    private var producerSurface: Surface? = null
    // Non-null while this clip is dewarped at playback (raw CPU-engine clip
    // with lens correction > 0). Owns the EGL stack feeding the TextureView.
    private var dewarpSpec: PlaybackDewarp.Spec? = null
    private var dewarp: PlaybackDewarp? = null
    // A released dewarp whose GL teardown overran its wait still owns the output
    // Surface; the next prepare waits for it (a second producer can't connect).
    private var teardownPending = false
    private var pendingUri: Uri? = null
    private var surfaceReady: Boolean = false
    private var prepared: Boolean = false
    private var playbackVolume: Float = 1f

    // Snapshot of the last-known playback position. Captured before the
    // SurfaceTexture is destroyed (on background, rotation, etc.) so the
    // re-prepared player can seek back to where the user left off — the
    // alternative is restarting every clip from frame zero whenever the
    // view detaches, which is the regression VideoView callers feared.
    private var pendingSeekMs: Int = 0
    // Whether the next onPrepared should auto-start. The fragment's
    // onPause runs *before* the SurfaceTexture is destroyed and unconditionally
    // pauses the MediaPlayer, so reading isPlaying at destroy-time always
    // sees false. Instead we provide [snapshotPlayingState] so the host
    // can capture the user's intent in its own onPause handler before
    // calling pause(); the host then reads [shouldAutoResume] in its
    // prepared listener to decide whether to start().
    private var resumeOnPrepare: Boolean = true
    // True once [snapshotPlayingState] has captured the host's intent for
    // the current prepare cycle. Cleared after each onPrepared fires.
    // When the SurfaceTexture is destroyed without an onPause path
    // (DialogFragment overlay, picture-in-picture, system permission
    // dialog), we fall back to capturing isPlaying directly in
    // onSurfaceTextureDestroyed so a paused-by-user clip doesn't
    // silently resume on surface re-create.
    private var hostSnapshotted: Boolean = false

    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    private var quadrant: Quadrant = Quadrant.ALL
    // Composition of the current clip; drives regionFor() + quadrantAtPoint().
    private var layout: Layout = Layout.STANDARD
    private var transformAnimator: ValueAnimator? = null

    // Tracks whether the producer has queued at least one frame to the
    // SurfaceTexture. On the BYD head-unit (Android 10 / DiLink),
    // calling setTransform() BEFORE the first frame lands silently
    // breaks the texture binding — MediaPlayer.start() succeeds, audio
    // ticks, but no frames ever paint. We defer the very first
    // applyTransform() until onSurfaceTextureUpdated fires, which is
    // the OS's signal that the queue is alive. Subsequent transforms
    // (quadrant zoom, size changes) are safe because the binding is
    // established.
    private var firstFrameSeen: Boolean = false
    // A resize or re-show made TextureView refresh the layer without a new frame;
    // the next onSurfaceTextureUpdated must not count as the first frame.
    private var layerRefreshPending: Boolean = false

    // Reused per-frame matrix. applyTransform runs once per animator tick
    // (~14 calls per gesture) plus on size changes; allocating fresh would
    // be ~harmless but the reuse pattern keeps the GL/UI thread quiet.
    private val scratchMatrix = Matrix()
    private val scratchClip = Rect()
    // Frame aspect the last applied transform fitted (0 = unfitted); taps are hit-tested against it.
    private var fitFrameAspect: Float = 0f

    // Listener pass-through — VideoView API parity for the fragment.
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var completionListener: MediaPlayer.OnCompletionListener? = null
    private var errorListener: MediaPlayer.OnErrorListener? = null

    /**
     * Notified when the user double-taps the video. The receiver decides
     * what to do (typical: zoom into the tapped quadrant, or reset to ALL
     * if already zoomed). Coordinates are passed in view-local space so
     * the receiver can map them back to a [Quadrant].
     */
    fun interface OnDoubleTapListener {
        fun onDoubleTap(targetQuadrant: Quadrant)
    }

    private var doubleTapListener: OnDoubleTapListener? = null

    /**
     * GestureDetector handles single-tap → click (for the chrome auto-hide
     * toggle) and double-tap → quadrant zoom. We can't use the standard
     * setOnClickListener path alone because it fires on every UP event,
     * including the first tap of a double-tap — that would make the
     * chrome flicker on every zoom gesture.
     *
     * The detector consumes the down event so the first finger-up gets
     * 300ms to either become a confirmed single-tap (onSingleTapConfirmed)
     * or a double-tap (onDoubleTap). Either way, no accidental click is
     * delivered to the host view.
     */
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = quadrantAtPoint(e.x, e.y)
            doubleTapListener?.onDoubleTap(target)
            return true
        }
    })

    init {
        surfaceTextureListener = this
        // No setBackground*() calls — TextureView throws
        // UnsupportedOperationException on every form of background drawable
        // (the producer surface IS the texture, there's no slot for a
        // separate background). The black letterbox we want around the
        // video comes from the parent layout's android:background="#000000"
        // in fragment_video_player.xml, which is what the user sees during
        // the brief "surface detached" window between clips anyway.
        isClickable = true
        // The fit transform leaves letterbox bands uncovered; TextureView requires non-opaque then.
        setOpaque(false)
    }

    @SuppressWarnings("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setOnDoubleTapListener(l: OnDoubleTapListener?) {
        doubleTapListener = l
    }

    /**
     * Map a view-local touch point to a [Quadrant]. When already zoomed,
     * double-tap is "reset to ALL". When zoomed out the hit-test mirrors the
     * on-screen camera regions of the fitted frame for the active [layout]:
     *   STANDARD — a 2x2 halves split.
     *   DASHCAM  — top [DASHCAM_SPLIT] band → FRONT; bottom band split into
     *              left/middle/right thirds → LEFT / REAR / RIGHT.
     * Taps on the letterbox bands resolve to the nearest region.
     */
    private fun quadrantAtPoint(x: Float, y: Float): Quadrant {
        if (quadrant != Quadrant.ALL) return Quadrant.ALL
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return Quadrant.ALL
        var cw = vw
        var ch = vh
        val frameAspect = fitFrameAspect
        if (frameAspect > 0f) {
            if (frameAspect < vw / vh) cw = vh * frameAspect else ch = vw / frameAspect
        }
        val fx = ((x - (vw - cw) / 2f) / cw).coerceIn(0f, 1f)
        val fy = ((y - (vh - ch) / 2f) / ch).coerceIn(0f, 1f)
        return when (layout) {
            Layout.DASHCAM ->
                if (fy < DASHCAM_SPLIT) {
                    Quadrant.FRONT
                } else {
                    when {
                        fx < DASHCAM_THIRD -> Quadrant.LEFT
                        fx < 2f * DASHCAM_THIRD -> Quadrant.REAR
                        else -> Quadrant.RIGHT
                    }
                }
            Layout.STANDARD -> {
                val left = fx < 0.5f
                val top = fy < 0.5f
                when {
                    top && left -> Quadrant.FRONT
                    top && !left -> Quadrant.RIGHT
                    !top && left -> Quadrant.REAR
                    else -> Quadrant.LEFT
                }
            }
        }
    }

    // --------- VideoView-shaped surface ---------

    fun setVideoURI(uri: Uri) {
        pendingUri = uri
        prepared = false
        firstFrameSeen = false
        // A new clip starts at zero. Distinct from the surface-recreate
        // path, which preserves pendingSeekMs to resume in place.
        pendingSeekMs = 0
        resumeOnPrepare = true
        hostSnapshotted = false
        // Tear down any prior player BEFORE we reset state — releasing
        // after-the-fact would race with the new prepare.
        releasePlayer()
        if (surfaceReady) startPreparing()
    }

    /** Next clip starts without lens correction; call before setVideoURI. */
    fun resetLensDewarp() {
        dewarpSpec = null
    }

    /** Per-clip lens tag (null = none). Usually lands after prepare began: the player restarts in place. */
    fun setLensDewarp(spec: PlaybackDewarp.Spec?) {
        if (spec == dewarpSpec) return
        dewarpSpec = spec
        if (mediaPlayer != null) restartInPlace()
    }

    /** Rebuilds the player on the same clip, keeping position and play/pause state. */
    private fun restartInPlace() {
        val mp = mediaPlayer
        if (mp != null && prepared) {
            try {
                pendingSeekMs = mp.currentPosition
                resumeOnPrepare = mp.isPlaying
            } catch (_: IllegalStateException) {
            }
        }
        releasePlayer()
        if (surfaceReady) startPreparing()
    }

    fun start() {
        val mp = mediaPlayer ?: run {
            Log.w(TAG, "start() with no mediaPlayer (surfaceReady=$surfaceReady prepared=$prepared)")
            return
        }
        Log.d(TAG, "start() prepared=$prepared isPlaying=${try { mp.isPlaying } catch (_: Exception) { "?" }}")
        if (prepared) {
            try { mp.start() } catch (e: IllegalStateException) {
                Log.e(TAG, "start() IllegalStateException", e)
            }
        }
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        if (prepared && mp.isPlaying) {
            try { mp.pause() } catch (_: IllegalStateException) {}
        }
    }

    fun stopPlayback() {
        releasePlayer()
        prepared = false
        pendingUri = null
        videoWidth = 0
        videoHeight = 0
        firstFrameSeen = false
    }

    /**
     * Set this view's MediaPlayer gain without changing its audio attributes or stream.
     * The value is retained across player/surface recreation. While prepareAsync is in
     * flight only the field changes; the latest value is applied once PREPARED, before
     * the host can start playback.
     */
    fun setPlaybackVolume(volume: Float) {
        playbackVolume = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 1f
        if (!prepared) return
        try {
            mediaPlayer?.setVolume(playbackVolume, playbackVolume)
        } catch (_: Throwable) {
        }
    }

    val isPlaying: Boolean
        get() = try { mediaPlayer?.isPlaying == true } catch (_: IllegalStateException) { false }

    val currentPosition: Int
        get() = try {
            if (prepared) mediaPlayer?.currentPosition ?: 0 else 0
        } catch (_: IllegalStateException) { 0 }

    val duration: Int
        get() = try {
            if (prepared) mediaPlayer?.duration ?: 0 else 0
        } catch (_: IllegalStateException) { 0 }

    fun seekTo(ms: Int) {
        if (prepared) {
            try { mediaPlayer?.seekTo(ms) } catch (_: IllegalStateException) {}
        }
    }

    fun setOnPreparedListener(l: MediaPlayer.OnPreparedListener?) {
        preparedListener = l
    }

    fun setOnCompletionListener(l: MediaPlayer.OnCompletionListener?) {
        completionListener = l
    }

    fun setOnErrorListener(l: MediaPlayer.OnErrorListener?) {
        errorListener = l
    }

    /**
     * Capture the current playing state into [resumeOnPrepare] so the
     * next prepare cycle (after a surface re-create) restores it. The
     * host should call this from its own `onPause` BEFORE calling
     * [pause] — otherwise the snapshot always reads false.
     */
    fun snapshotPlayingState() {
        val mp = mediaPlayer ?: return
        if (!prepared) return
        try {
            resumeOnPrepare = mp.isPlaying
            hostSnapshotted = true
        } catch (_: IllegalStateException) {
            resumeOnPrepare = false
            hostSnapshotted = true
        }
    }

    /**
     * Whether the next prepare-after-surface-recreate should auto-resume
     * playback. True for first-time prepare (autoplay) and for surface
     * recreates where the user was playing; false when the host paused
     * before backgrounding. Cleared after each prepare cycle.
     */
    fun shouldAutoResume(): Boolean = resumeOnPrepare

    /**
     * Manually program the next prepare cycle's seek + resume policy.
     * Used by hosts that survive config changes (rotation) — they
     * stash the playhead in onSaveInstanceState and call this on the
     * recreated view BEFORE / right after [setVideoURI] so the new
     * MediaPlayer seeks to the saved position instead of frame zero.
     */
    fun primeResume(positionMs: Int, autoResume: Boolean) {
        // coerceAtLeast(0) instead of `if (positionMs > 0)` — the explicit
        // overwrite (even with 0) keeps semantics symmetric: primeResume
        // is the authoritative voice for the next prepare cycle, and
        // callers shouldn't have to know that 0 is a "soft" value.
        pendingSeekMs = positionMs.coerceAtLeast(0)
        resumeOnPrepare = autoResume
        hostSnapshotted = true
    }

    // --------- Quadrant zoom ---------

    /**
     * Switch the visible quadrant. Animates the texture transform over
     * 240ms with a Material decelerate curve so the camera change is
     * legible (a hard jump misreads as a glitch). No-op if [target]
     * already matches the current quadrant.
     *
     * Pre-layout calls (width or height == 0): we update the field
     * eagerly so subsequent state-readers see the new value, but skip
     * the animator. [onSizeChanged] picks up the field once layout
     * completes and paints the matching matrix on the first frame.
     */
    fun setQuadrant(target: Quadrant, animate: Boolean = true) {
        if (target == quadrant) return
        val from = quadrant
        quadrant = target
        if (width <= 0 || height <= 0) {
            // Pre-layout: user clicks aren't physically possible (the
            // chrome buttons aren't laid out either), so animate=true here
            // can only come from a programmatic restore. We've already
            // updated the field; onSurfaceTextureUpdated will paint it as
            // a snap once the first frame is queued.
            return
        }
        // Pre-first-frame: don't apply the matrix yet (the BYD head-unit (Android 10) bug —
        // setTransform before producer queues a buffer breaks the binding
        // and freezes playback). Field is updated, so the first
        // applyTransform from onSurfaceTextureUpdated will paint the new
        // quadrant directly.
        if (!firstFrameSeen) {
            return
        }
        if (!animate) {
            applyTransform(progress = 1f, from = target, to = target)
            return
        }
        transformAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = MATERIAL_EMPHASIZED_DECELERATE
            addUpdateListener { applyTransform(it.animatedValue as Float, from, target) }
        }
        transformAnimator = anim
        anim.start()
    }

    fun getQuadrant(): Quadrant = quadrant

    /**
     * Set the clip's composition [Layout]. Changes which sub-rectangle each
     * [Quadrant] maps to (e.g. FRONT is the top-left quarter in STANDARD but
     * the full-width top band in DASHCAM), so the host calls this per clip
     * (from the recording's sidecar `layout` tag) before/around [setVideoURI].
     *
     * Pre-first-frame it just updates the field — the first paint reads it, so
     * no flash. Mid-clip it snaps the current quadrant to the new geometry.
     */
    fun setLayout(newLayout: Layout) {
        if (newLayout == layout) return
        layout = newLayout
        if (firstFrameSeen && width > 0 && height > 0) {
            transformAnimator?.cancel()
            applyTransform(progress = 1f, from = quadrant, to = quadrant)
        }
    }

    fun getLayout(): Layout = layout

    // --------- Internals ---------

    private fun startPreparing() {
        if (teardownPending) return
        val uri = pendingUri ?: return
        val texture = surfaceTexture ?: return
        prepared = false
        val mp = MediaPlayer()
        mediaPlayer = mp
        try {
            // Use MediaPlayer's default audio attributes (USAGE_MEDIA +
            // CONTENT_TYPE_MOVIE). The previous attempt to use
            // USAGE_ASSISTANCE_SONIFICATION to mix with the radio caused
            // a mid-prepare stream-type reassignment (visible as
            // "reassignAudioAttributes streamType=3 → streamType=1" in
            // logcat) which on the BYD/DiLink audio stack stalled the
            // codec — playback "stuck" with audio system registered but
            // no frames delivered.
            try {
                mp.setVolume(playbackVolume, playbackVolume)
            } catch (_: Throwable) {
            }
            mp.setDataSource(context, uri)
            // Hold a strong ref so the GC doesn't collect this Surface
            // while MediaPlayer is using it (see producerSurface field
            // comment for the the BYD head-unit (Android 10) ramifications).
            val surface = Surface(texture)
            producerSurface = surface
            // Dewarp path for raw CPU clips: route MediaPlayer into an EGL
            // pipeline whose output lands on `surface`. Any failure plays the
            // clip uncorrected.
            val spec = dewarpSpec
            var mpSurface = surface
            if (spec != null) {
                val dewarpInput = buildDewarp(texture, surface, spec)
                if (dewarpInput != null) {
                    mpSurface = dewarpInput
                } else {
                    dewarpSpec = null
                    if (teardownPending) {
                        // EGL still holds `surface`; prepare again once it lets go.
                        releasePlayer()
                        return
                    }
                }
            }
            mp.setSurface(mpSurface)
            mp.setOnPreparedListener { player ->
                prepared = true
                // A RoadSense duck transition may have happened during prepareAsync.
                // Apply the latest retained gain in PREPARED before the host starts.
                try {
                    player.setVolume(playbackVolume, playbackVolume)
                } catch (_: Throwable) {
                }
                videoWidth = player.videoWidth
                videoHeight = player.videoHeight
                Log.d(TAG, "onPrepared: dims=${videoWidth}x${videoHeight} viewSize=${width}x${height}")
                // DO NOT call applyTransform here. On the BYD head-unit (Android 10), calling
                // setTransform() before the first frame is queued silently
                // breaks the SurfaceTexture binding — start() succeeds but
                // no frames paint. The first applyTransform fires from
                // onSurfaceTextureUpdated once the producer is alive.
                // Restore playback position when this prepare follows a
                // SurfaceTexture re-create (background → resume). Skip
                // when the seek would be at zero — start-of-clip seeks
                // are wasteful and on some BYD-era MediaPlayer builds
                // emit a stuttery first frame. We don't start() here —
                // the host's prepared listener owns the auto-play decision
                // (it also has to apply mute first to avoid a louder-
                // than-expected first frame).
                if (pendingSeekMs > 0) {
                    try { player.seekTo(pendingSeekMs) } catch (_: IllegalStateException) {}
                }
                preparedListener?.onPrepared(player)
                // Reset the resume flags now that we've handed off to the
                // host. Subsequent surface-destroy events will repopulate.
                // hostSnapshotted resets too so the next destroy path can
                // snapshot fresh — either via the host's onPause hook or
                // via our own fallback in onSurfaceTextureDestroyed.
                pendingSeekMs = 0
                resumeOnPrepare = true
                hostSnapshotted = false
            }
            mp.setOnCompletionListener { player ->
                completionListener?.onCompletion(player)
            }
            mp.setOnErrorListener { player, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                prepared = false
                errorListener?.onError(player, what, extra) ?: false
            }
            mp.setOnInfoListener { _, what, extra ->
                Log.d(TAG, "MediaPlayer info: what=$what extra=$extra")
                false
            }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
                    videoWidth = w
                    videoHeight = h
                    // Same gate as onPrepared — applyTransform only after
                    // the first frame is on the surface. If frames are
                    // already flowing (size-change mid-clip, rare), it's
                    // safe to repaint now.
                    if (firstFrameSeen) {
                        applyTransform(progress = 1f, from = quadrant, to = quadrant)
                    }
                }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            errorListener?.onError(mp, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        prepared = false
        transformAnimator?.cancel()
        transformAnimator = null
        val mp = mediaPlayer
        if (mp != null) {
            try { mp.setOnPreparedListener(null) } catch (_: Exception) {}
            try { mp.setOnCompletionListener(null) } catch (_: Exception) {}
            try { mp.setOnErrorListener(null) } catch (_: Exception) {}
            try { mp.setOnVideoSizeChangedListener(null) } catch (_: Exception) {}
            try { mp.setOnInfoListener(null) } catch (_: Exception) {}
            try { mp.reset() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
            mediaPlayer = null
        }
        // Release the Surface AFTER the player so MediaPlayer's internal
        // teardown doesn't touch a freed producer. Tracker field cleared
        // unconditionally so a partial-init failure doesn't leave a
        // dangling reference around.
        releaseDewarp()
        try { producerSurface?.release() } catch (_: Exception) {}
        producerSurface = null
    }

    private fun buildDewarp(texture: SurfaceTexture, outputSurface: Surface, spec: PlaybackDewarp.Spec): Surface? {
        return try {
            // Frame-sized output keeps a zoomed quadrant at full detail.
            texture.setDefaultBufferSize(spec.frameWidth, spec.frameHeight)
            val d = PlaybackDewarp(outputSurface, spec) { failed -> post { onDewarpFailed(failed) } }
            dewarp = d
            d.start()
        } catch (t: Throwable) {
            Log.w(TAG, "dewarp init failed, playing uncorrected: ${t.message}")
            releaseDewarp()
            restoreViewBufferSize(texture)
            null
        }
    }

    private fun releaseDewarp() {
        val d = dewarp ?: return
        dewarp = null
        val freed = try {
            d.release(onReleased = { post { onDewarpTeardown() } })
        } catch (_: Exception) {
            true
        }
        if (!freed) teardownPending = true
    }

    private fun onDewarpTeardown() {
        if (!teardownPending) return
        teardownPending = false
        if (pendingUri != null && mediaPlayer == null && surfaceReady) startPreparing()
    }

    /** Main thread: a dewarp that failed mid-clip; the player restarts uncorrected (released first, so no producer clash). */
    private fun onDewarpFailed(failed: PlaybackDewarp) {
        if (dewarp !== failed) return
        dewarpSpec = null
        restartInPlace()
        surfaceTexture?.let { restoreViewBufferSize(it) }
    }

    private fun restoreViewBufferSize(texture: SurfaceTexture) {
        if (width <= 0 || height <= 0) return
        try { texture.setDefaultBufferSize(width, height) } catch (_: Exception) {}
    }

    /**
     * Compose the active matrix for this frame.
     *
     * Region-based fit. Each [Quadrant] maps, for the active [layout], to a
     * normalized sub-rectangle of the source frame ([regionFor]); the matrix
     * scales + translates that rectangle to fill the view, then shrinks one
     * axis about the centre so it keeps its true pixel aspect, and clips the
     * view to that rectangle (letterbox bands show the parent's black background).
     *
     * For the STANDARD 2x2 regions this is identical to the previous
     * postScale(2, 2)-around-a-view-corner formulation (e.g. FRONT = the
     * (0,0,½,½) rect → scale 2 anchored top-left). DASHCAM uses the front
     * top-band and the bottom-thirds rects instead.
     *
     * Transitions interpolate the source rectangle (x, y, w, h) over
     * [progress] (Material 3 emphasized decelerate, set by the animator), so
     * scale and offset move together.
     */
    private fun applyTransform(progress: Float, from: Quadrant, to: Quadrant) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val r0 = regionFor(layout, from)
        val r1 = regionFor(layout, to)
        val rx = r0.x + (r1.x - r0.x) * progress
        val ry = r0.y + (r1.y - r0.y) * progress
        val rw = r0.w + (r1.w - r0.w) * progress
        val rh = r0.h + (r1.h - r0.h) * progress

        // Fill the view with the [rx,ry,rw,rh] sub-rectangle: scale so the
        // rect spans the whole view, then slide its top-left to the origin.
        val sx = if (rw > 0f) 1f / rw else 1f
        val sy = if (rh > 0f) 1f / rh else 1f

        val matrix = scratchMatrix
        matrix.setScale(sx, sy)
        matrix.postTranslate(-rx * vw * sx, -ry * vh * sy)
        var clip: Rect? = null
        if (videoWidth > 0 && videoHeight > 0 && rw > 0f && rh > 0f) {
            val regionAspect = (rw * videoWidth) / (rh * videoHeight)
            val viewAspect = vw / vh
            var fw = vw
            var fh = vh
            if (regionAspect < viewAspect) {
                fw = vw * regionAspect / viewAspect
                matrix.postScale(regionAspect / viewAspect, 1f, vw / 2f, vh / 2f)
            } else if (regionAspect > viewAspect) {
                fh = vh * viewAspect / regionAspect
                matrix.postScale(1f, viewAspect / regionAspect, vw / 2f, vh / 2f)
            }
            // The texture extends past the region; clip so neighbouring cameras stay out of the bands.
            clip = scratchClip.apply {
                set(ceil((vw - fw) / 2f).toInt(), ceil((vh - fh) / 2f).toInt(),
                    floor((vw + fw) / 2f).toInt(), floor((vh + fh) / 2f).toInt())
            }
            fitFrameAspect = videoWidth.toFloat() / videoHeight
        } else {
            fitFrameAspect = 0f
        }
        setTransform(matrix)
        setClipBounds(clip)
        invalidate()
    }

    /** Normalized sub-rectangle (x, y, w, h ∈ 0..1) of the source frame. */
    private class Region(val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * The source-frame sub-rectangle a [Quadrant] occupies for a given
     * [layout]. Mirrors GpuMosaicRecorder's shader composition exactly so
     * the zoom lands on the right camera:
     *   STANDARD — four quarters of the 2x2 grid.
     *   DASHCAM  — FRONT = the full-width top [DASHCAM_SPLIT] band; LEFT /
     *              REAR / RIGHT = the left / middle / right thirds of the
     *              bottom band.
     * ALL = the whole frame (identity zoom) in both layouts.
     */
    private fun regionFor(layout: Layout, q: Quadrant): Region = when (layout) {
        Layout.STANDARD -> when (q) {
            Quadrant.ALL -> Region(0f, 0f, 1f, 1f)
            Quadrant.FRONT -> Region(0f, 0f, 0.5f, 0.5f)
            Quadrant.RIGHT -> Region(0.5f, 0f, 0.5f, 0.5f)
            Quadrant.REAR -> Region(0f, 0.5f, 0.5f, 0.5f)
            Quadrant.LEFT -> Region(0.5f, 0.5f, 0.5f, 0.5f)
        }
        Layout.DASHCAM -> when (q) {
            Quadrant.ALL -> Region(0f, 0f, 1f, 1f)
            Quadrant.FRONT -> Region(0f, 0f, 1f, DASHCAM_SPLIT)
            Quadrant.LEFT -> Region(0f, DASHCAM_SPLIT, DASHCAM_THIRD, DASHCAM_BOTTOM)
            Quadrant.REAR -> Region(DASHCAM_THIRD, DASHCAM_SPLIT, DASHCAM_THIRD, DASHCAM_BOTTOM)
            Quadrant.RIGHT -> Region(2f * DASHCAM_THIRD, DASHCAM_SPLIT, DASHCAM_THIRD, DASHCAM_BOTTOM)
        }
    }

    /*
     * Note on aspect-correct sizing: events.html sets the wrapper's
     * aspect-ratio inline on loadedmetadata so corner zoom fills cleanly.
     * The Android equivalent (override onMeasure + requestLayout from
     * onPrepared) was tried and reverted: on the BYD head-unit (Android 10) (BYD head-unit
     * floor) any setMeasuredDimension change AFTER the SurfaceTexture is
     * bound to a MediaPlayer surface destroys the SurfaceTexture, which
     * tears down our Surface, and onSurfaceTextureDestroyed releases the
     * MediaPlayer → playback stalls on click.
     *
     * Solution: compensate inside the matrix instead. applyTransform
     * scales the texture to source-aspect within the view bounds (so
     * a 4:3 mosaic in a wider slot is rendered as a centered 4:3 rect
     * with parent-FrameLayout black bands either side), then applies
     * the corner-pivot zoom relative to THAT rect. Same visible result
     * as the web side, no SurfaceTexture churn.
     */

    // --------- SurfaceTextureListener ---------

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureAvailable: ${width}x${height}")
        surfaceReady = true
        // Same value TextureView already sets. Decoder output is unaffected: MediaCodec
        // sets explicit buffer dimensions, so frames keep the stream's native size.
        try {
            surface.setDefaultBufferSize(width, height)
        } catch (_: Exception) { /* best effort */ }
        // Kick the TextureView matrix binding with the identity matrix.
        // On some the BYD head-unit (Android 10) builds (BYD head-unit included) the
        // TextureView won't pump frames from its SurfaceTexture to its
        // hardware layer until setTransform has been called at least
        // once — even with the identity matrix. Without this, MediaPlayer
        // produces frames into the SurfaceTexture forever and they never
        // make it onto the screen.
        try {
            setTransform(scratchMatrix.apply { reset() })
        } catch (_: Exception) { /* best effort */ }
        // Identity has no fit; the new surface's first frame re-applies clip + fit.
        setClipBounds(null)
        fitFrameAspect = 0f
        if (pendingUri != null && mediaPlayer == null) startPreparing()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureSizeChanged: ${width}x${height}")
        // TextureView just reset the buffer to the view size; the dewarp renders at frame size.
        val spec = dewarpSpec
        if (dewarp != null && spec != null) {
            try { surface.setDefaultBufferSize(spec.frameWidth, spec.frameHeight) } catch (_: Exception) {}
        }
        // Same first-frame gate — pre-frame setTransform breaks the texture
        // binding on the BYD head-unit (Android 10). After the first frame this is safe and
        // important (parent layout may resize the view at any time).
        if (firstFrameSeen) {
            applyTransform(progress = 1f, from = quadrant, to = quadrant)
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Log.d(TAG, "onSurfaceTextureDestroyed (prepared=$prepared)")
        // Snapshot the playhead BEFORE releasing so the re-prepared player
        // can seek back to where the user left off.
        //
        // resumeOnPrepare: the host captures this via [snapshotPlayingState]
        // in its onPause (which sets [hostSnapshotted]). For transient
        // surface destroys without onPause (DialogFragment overlay,
        // picture-in-picture, system permission dialog) the host hasn't
        // set the flag — fall back to reading isPlaying ourselves so a
        // paused-by-user clip doesn't silently resume on surface re-create.
        val mp = mediaPlayer
        if (mp != null && prepared) {
            try {
                pendingSeekMs = mp.currentPosition
                if (!hostSnapshotted) {
                    resumeOnPrepare = mp.isPlaying
                }
            } catch (_: IllegalStateException) {
                // Transitioned out of a valid state during teardown;
                // explicitly zero so the next prepare doesn't carry a
                // stale seek from an earlier clip.
                pendingSeekMs = 0
                if (!hostSnapshotted) resumeOnPrepare = false
            }
        }
        surfaceReady = false
        // A re-created surface is a new binding: its first frame must re-apply the transform.
        firstFrameSeen = false
        releasePlayer()
        return true  // safe to release the texture
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Fires every time the producer queues a frame. The first one is
        // the OS's signal that the texture binding is alive, which is
        // when it's finally safe to apply our matrix transform on Android
        // 7.1. Doing it earlier silently breaks playback (start() works,
        // audio runs, but no frames paint — see firstFrameSeen comment).
        // TextureView also replays a stale pending update after re-attach or a player
        // swap; the current player can only have drawn once it is prepared.
        val refreshOnly = layerRefreshPending
        layerRefreshPending = false
        // TextureView can reset the buffer size without a size callback (layer rebuilt after stop/start).
        val spec = dewarpSpec
        if (dewarp != null && spec != null) {
            try { surface.setDefaultBufferSize(spec.frameWidth, spec.frameHeight) } catch (_: Exception) {}
        }
        if (!firstFrameSeen && prepared && !refreshOnly) {
            firstFrameSeen = true
            Log.d(TAG, "first frame queued — applying transform")
            applyTransform(progress = 1f, from = quadrant, to = quadrant)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Same first-frame gate. onSizeChanged fires during initial layout
        // BEFORE the producer queues anything, so the unconditional call
        // we used to make here was the original instance of the bug.
        if (firstFrameSeen) {
            applyTransform(progress = 1f, from = quadrant, to = quadrant)
        } else if (isAvailable) {
            layerRefreshPending = true
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Mirrors TextureView: becoming VISIBLE with a surface refreshes the layer without a frame.
        if (visibility == VISIBLE && isAvailable && !firstFrameSeen) {
            layerRefreshPending = true
        }
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "ZoomableVideoView"
        // Material 3 emphasized decelerate curve.
        private val MATERIAL_EMPHASIZED_DECELERATE =
            PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)

        // Dashcam composition geometry — MUST match GpuMosaicRecorder's
        // dashcam shader branch: the front slice fills the top SPLIT band
        // (full width); left/rear/right tile the bottom band in thirds.
        private const val DASHCAM_SPLIT = 0.70f
        private const val DASHCAM_BOTTOM = 0.30f   // 1 - DASHCAM_SPLIT
        private const val DASHCAM_THIRD = 1f / 3f
    }
}
