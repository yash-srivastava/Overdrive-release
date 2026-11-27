package com.overdrive.app.ui.timeline.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import com.overdrive.app.ui.timeline.ClipSpec
import com.overdrive.app.ui.timeline.player.FrameSink
import com.overdrive.app.ui.view.ZoomableVideoView.Quadrant

/**
 * Timeline video surface: the decoder Surface outlives this view's own surface, and all geometry is drawn
 * in GL, so the TextureView transform stays identity and the view never re-measures for the video.
 * Callbacks and setters are main-thread; [FrameSink] calls are accepted from any thread.
 */
class TimelineVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener, FrameSink {

    /** Delivered again to a newly set callback while the Surface is valid. */
    var onDecoderSurface: ((Surface) -> Unit)? = null
        set(value) {
            field = value
            val s = decoderSurface
            if (value != null && s != null) value(s)
        }

    /** The last delivered Surface must no longer be rendered into. */
    var onDecoderSurfaceLost: (() -> Unit)? = null

    /** GL dropped the follower Surface (rebuild or release); called on the GL thread. */
    @Volatile var onFollowerSurfaceLost: (() -> Unit)? = null

    /** Camera shown after a tap; ALL when the tap returned to every camera. */
    var onCameraTapped: ((Quadrant) -> Unit)? = null

    /** Visible camera tiles in view px whenever crop, zoom, layout or size moves them. */
    var onTilesChanged: ((List<CameraTile>) -> Unit)? = null

    /** Camera currently shown (ALL = every camera). */
    var camera: Quadrant = Quadrant.ALL
        private set

    private val main = Handler(Looper.getMainLooper())
    private val identity = Matrix()
    private var decoderSurface: Surface? = null
    private var released = false
    private var flingVx = 0f
    private var flingVy = 0f

    private val rendererHost = object : TimelineGlRenderer.Host {
        override fun onDecoderSurface(surface: Surface) {
            main.post {
                if (released) return@post
                decoderSurface = surface
                onDecoderSurface?.invoke(surface)
            }
        }

        override fun onDecoderSurfaceLost() {
            main.post {
                if (decoderSurface == null) return@post
                decoderSurface = null
                onDecoderSurfaceLost?.invoke()
            }
        }

        override fun onCameraTapped(camera: Quadrant) {
            main.post {
                if (released) return@post
                this@TimelineVideoView.camera = camera
                onCameraTapped?.invoke(camera)
            }
        }

        override fun onFollowerSurfaceLost() {
            onFollowerSurfaceLost?.invoke()
        }

        override fun onGeometry(camera: Quadrant, tiles: List<CameraTile>) {
            main.post {
                if (released) return@post
                this@TimelineVideoView.camera = camera
                onTilesChanged?.invoke(tiles)
            }
        }
    }

    @Volatile private var renderer: TimelineGlRenderer? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            renderer?.pan(-distanceX, -distanceY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            flingVx = velocityX
            flingVy = velocityY
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            renderer?.tap(e.x, e.y)
            performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            renderer?.doubleTap(e.x, e.y)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer?.scale(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    init {
        surfaceTextureListener = this
        isClickable = true
        // Letterbox bands stay transparent; TextureView requires non-opaque then.
        setOpaque(false)
        if (!isInEditMode) renderer = TimelineGlRenderer(rendererHost).also { it.start() }
    }

    fun setVideoSize(w: Int, h: Int) {
        renderer?.setVideoSize(w, h)
    }

    /** Applies [spec] to the frame on screen now; the player's scheduled specs still apply on their frames. */
    fun setClipSpecNow(spec: ClipSpec?) {
        renderer?.setClipSpecNow(spec)
    }

    fun setCamera(q: Quadrant, animate: Boolean) {
        renderer?.setCamera(q, animate)
    }

    /** Live lens slider (0 = passthrough); overrides the coefficients carried by clip specs. */
    fun setDewarpStrength(strength0to100: Int) {
        renderer?.setDewarpStrength(strength0to100)
    }

    fun resetZoom(animate: Boolean) {
        renderer?.resetZoom(animate)
    }

    /** Dashcam view follower input; [onSurface] runs on the GL thread, null when unavailable. Any thread. */
    fun openFollowerInput(onSurface: (Surface?) -> Unit): Boolean {
        val r = renderer ?: return false
        r.openFollowerInput(onSurface)
        return true
    }

    /** Keeps the picture on screen until the Dashcam view is drawn (at most [maxWaitMs]). */
    fun holdForComposite(maxWaitMs: Long) {
        renderer?.holdForComposite(maxWaitMs)
    }

    fun closeFollowerInput() {
        renderer?.closeFollowerInput()
    }

    fun setFollowerShown(on: Boolean) {
        renderer?.setFollowerShown(on)
    }

    fun setFollowerVideoSize(w: Int, h: Int) {
        renderer?.setFollowerVideoSize(w, h)
    }

    /** Follower frames latched so far (monotonic while the input lives). */
    fun followerFrames(): Int = renderer?.followerFrames ?: 0

    /** Median follower frame interval in real-time ns, 0 when unknown. */
    fun followerFrameNs(): Long = renderer?.followerFrameNs ?: 0L

    /** Frees the GL thread and the decoder Surface within ~500 ms; the view is inert afterwards. */
    fun release() {
        if (released) return
        released = true
        val r = renderer
        renderer = null
        main.removeCallbacksAndMessages(null)
        if (decoderSurface != null) {
            decoderSurface = null
            onDecoderSurfaceLost?.invoke()
        }
        r?.release(RELEASE_TIMEOUT_MS)
    }

    /** The spec takes effect on the first frame whose render timestamp reaches [renderTimeNs]. */
    override fun scheduleClipSpec(renderTimeNs: Long, spec: ClipSpec?) {
        renderer?.scheduleClipSpec(renderTimeNs, spec)
    }

    /** Blends from the held picture (holdFrame, or the picture just before that frame) over [durationMs]. */
    override fun scheduleCrossfade(renderTimeNs: Long, durationMs: Long) {
        renderer?.scheduleCrossfade(renderTimeNs, durationMs)
    }

    /** Freezes the current picture until a scheduled crossfade starts, or a frame arrives with none pending. */
    override fun holdFrame() {
        renderer?.holdFrame()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = renderer ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flingVx = 0f
                flingVy = 0f
                r.gestureBegin()
                if (r.zoomed) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        // Pan by the focal movement first, then scale about the new focus: the grabbed point stays put.
        gestures.onTouchEvent(event)
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> r.gestureEnd(flingVx, flingVy)
            MotionEvent.ACTION_CANCEL -> r.gestureEnd(0f, 0f)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // Identity only: some head units pump TextureView frames only after a first setTransform.
        try {
            setTransform(identity)
        } catch (_: Exception) {
        }
        renderer?.surfaceAvailable(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderer?.surfaceSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val r = renderer ?: return true
        return !r.surfaceDestroyed(surface)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private companion object {
        const val RELEASE_TIMEOUT_MS = 500L
    }
}
