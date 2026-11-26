package com.overdrive.app.remote

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.RemoteMainActivity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Renders a second, real Overdrive Activity onto an app-owned private display.
 *
 * Display stack 0 and BYD's AccAnimation layer are never part of this display.
 * The ImageReader surface matches the normal 1920x1080 / 240-dpi Activity
 * composition. Frames are downscaled to 960x540 only after the complete
 * display buffer has been read; BYD's Android 10 compositor otherwise crops a
 * full-size projection into a smaller consumer surface instead of scaling it.
 * The newest image is JPEG encoded at a bounded cadence and cached, so web
 * clients do not initiate UI screenshots.
 */
object RemoteDevVirtualDisplay {
    const val BACKEND_NAME = "Virtual display"
    const val COMPATIBILITY_BACKEND_NAME = "Physical window compatibility"

    private const val TAG = "RemoteDevVirtualDisplay"
    private const val DISPLAY_NAME = "Overdrive Remote Dev"
    private const val LOGICAL_WIDTH = 1920
    private const val LOGICAL_HEIGHT = 1080
    private const val LOGICAL_DENSITY_DPI = 240
    private const val STREAM_WIDTH = 960
    private const val STREAM_HEIGHT = 540
    private const val STREAM_JPEG_QUALITY = 60
    private const val STREAM_FRAME_INTERVAL_MS = 100L
    private const val MAX_IMAGES = 3
    private const val BLACK_SAMPLE_COLUMNS = 24
    private const val BLACK_SAMPLE_ROWS = 14
    private const val BLACK_LUMA_THRESHOLD = 8
    private const val BLACK_NON_DARK_SAMPLE_LIMIT = 2

    data class StartResult(
        val success: Boolean,
        val displayId: Int = Display.INVALID_DISPLAY,
        val detail: String? = null,
        val backend: String = BACKEND_NAME,
        val compatibilityMode: Boolean = false,
    )

    data class CachedFrame(
        val sequence: Long,
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val encodedAtElapsedMs: Long,
        val encodeMs: Long,
        val droppedBlackFrames: Long,
    )

    private val lock = Any()
    @Volatile private var active: Session? = null
    @Volatile private var physicalCompatibilityMode = false

    fun start(application: Application): StartResult = synchronized(lock) {
        if (!RemoteDevViewController.isAccessAllowed()) {
            return StartResult(
                false,
                detail = RemoteDevViewController.ACCESS_LOCKED_DETAIL,
                backend = currentBackendName(),
                compatibilityMode = physicalCompatibilityMode,
            )
        }
        if (physicalCompatibilityMode) {
            return physicalCompatibilityResult()
        }
        active?.takeIf { it.isUsable() }?.let { session ->
            return try {
                session.launchActivity()
                StartResult(true, session.displayId)
            } catch (error: Throwable) {
                handleLaunchFailure(session, error)
            }
        }

        active?.close()
        active = null
        val session = try {
            Session(application)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to create private virtual display", error)
            return StartResult(false, detail = error.javaClass.simpleName + ": " + error.message)
        }
        if (!session.isActivityLaunchAllowed()) {
            session.close()
            return physicalCompatibilityResult(
                "Android rejected the Remote Dev View activity on display ${session.displayId}",
            )
        }
        active = session
        RemoteDevViewController.bindRemoteDisplay(session.displayId)
        return try {
            session.launchActivity()
            StartResult(true, session.displayId)
        } catch (error: Throwable) {
            handleLaunchFailure(session, error)
        }
    }

    fun stop() {
        val session = synchronized(lock) {
            physicalCompatibilityMode = false
            active.also { active = null }
        }
        if (session != null) {
            RemoteDevViewController.unbindRemoteDisplay(session.displayId)
            session.close()
        }
    }

    fun isRunning(): Boolean = active?.isUsable() == true

    fun isCompatibilityMode(): Boolean = physicalCompatibilityMode

    fun currentBackendName(): String =
        if (physicalCompatibilityMode) COMPATIBILITY_BACKEND_NAME else BACKEND_NAME

    fun displayId(): Int = when {
        physicalCompatibilityMode -> Display.DEFAULT_DISPLAY
        else -> active?.displayId ?: Display.INVALID_DISPLAY
    }

    fun latestFrame(): CachedFrame? =
        if (RemoteDevViewController.isAccessAllowed()) active?.latestFrame?.get() else null

    fun requestImmediateFrame() {
        active?.requestImmediateFrame()
    }

    private fun handleLaunchFailure(session: Session, error: Throwable): StartResult {
        Log.e(TAG, "Unable to launch RemoteMainActivity", error)
        active = null
        RemoteDevViewController.unbindRemoteDisplay(session.displayId)
        session.close()
        return if (error is SecurityException) {
            physicalCompatibilityResult(
                "Android threw SecurityException for display ${session.displayId}",
            )
        } else {
            StartResult(false, detail = error.javaClass.simpleName + ": " + error.message)
        }
    }

    private fun physicalCompatibilityResult(reason: String? = null): StartResult {
        physicalCompatibilityMode = true
        if (reason != null) {
            Log.w(TAG, "$reason; using the existing physical Overdrive window")
        }
        val status = RemoteDevViewController.physicalCompatibilityStatus()
        return StartResult(
            success = status.success,
            displayId = if (status.success) Display.DEFAULT_DISPLAY else Display.INVALID_DISPLAY,
            detail = if (status.success) null else status.detail,
            backend = COMPATIBILITY_BACKEND_NAME,
            compatibilityMode = true,
        )
    }

    private class Session(private val application: Application) {
        private val closed = AtomicBoolean(false)
        private val encoding = AtomicBoolean(false)
        private val forceNextFrame = AtomicBoolean(true)
        private val frameSequence = AtomicLong()
        private val droppedBlackFrames = AtomicLong()
        private val thread = HandlerThread("remote-dev-virtual-display").apply { start() }
        private val handler = Handler(thread.looper)
        private val encoder = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "remote-dev-frame-encoder").apply { isDaemon = true }
        }
        private val reader = ImageReader.newInstance(
            LOGICAL_WIDTH,
            LOGICAL_HEIGHT,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        )
        private val virtualDisplay: VirtualDisplay
        @Volatile private var nextEncodeAtElapsedMs = 0L
        @Volatile private var stagingBitmap: Bitmap? = null
        @Volatile private var outputBitmap: Bitmap? = null
        // Only the single encoder thread touches these scratch objects. A published
        // JPEG is always a detached byte array, never the reusable backing storage.
        private val jpegBuffer by lazy(LazyThreadSafetyMode.NONE) { FrameJpegBuffer() }
        private val sourceRect = Rect(0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT)
        private val destinationRect = Rect(0, 0, STREAM_WIDTH, STREAM_HEIGHT)
        private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val latestFrame = AtomicReference<CachedFrame>()
        val displayId: Int

        init {
            reader.setOnImageAvailableListener({ source -> onImageAvailable(source) }, handler)
            val displayManager = application.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            virtualDisplay = displayManager.createVirtualDisplay(
                DISPLAY_NAME,
                LOGICAL_WIDTH,
                LOGICAL_HEIGHT,
                LOGICAL_DENSITY_DPI,
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                object : VirtualDisplay.Callback() {
                    override fun onStopped() {
                        Log.i(TAG, "Private virtual display stopped")
                    }
                },
                handler,
            ) ?: throw IllegalStateException("DisplayManager returned no virtual display")
            displayId = virtualDisplay.display.displayId
            if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) {
                virtualDisplay.release()
                throw IllegalStateException("Invalid private display id $displayId")
            }
            Log.i(TAG, "Private virtual display ready: id=$displayId logical=${LOGICAL_WIDTH}x$LOGICAL_HEIGHT stream=${STREAM_WIDTH}x$STREAM_HEIGHT")
        }

        fun isUsable(): Boolean = !closed.get() && virtualDisplay.display.isValid

        fun isActivityLaunchAllowed(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            return try {
                val activityManager = application.getSystemService(ActivityManager::class.java)
                activityManager?.isActivityStartAllowedOnDisplay(
                    application,
                    displayId,
                    launchIntent(),
                ) ?: true
            } catch (error: Throwable) {
                // A missing or OEM-modified probe must not change behavior on
                // devices where the existing virtual-display path works.
                Log.w(TAG, "Unable to preflight activity launch on display $displayId", error)
                true
            }
        }

        fun launchActivity() {
            check(isUsable()) { "Private virtual display is not available" }
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
            application.startActivity(launchIntent(), options.toBundle())
        }

        private fun launchIntent() =
            Intent(application, RemoteMainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_REMOTE_DEV_SESSION, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }

        fun requestImmediateFrame() {
            forceNextFrame.set(true)
            nextEncodeAtElapsedMs = 0L
        }

        private fun onImageAvailable(source: ImageReader) {
            val image = try { source.acquireLatestImage() } catch (_: Throwable) { null } ?: return
            val now = SystemClock.elapsedRealtime()
            val forced = forceNextFrame.getAndSet(false)
            if (!forced && now < nextEncodeAtElapsedMs) {
                image.close()
                return
            }
            if (!encoding.compareAndSet(false, true)) {
                forceNextFrame.set(forced)
                image.close()
                return
            }
            nextEncodeAtElapsedMs = now + STREAM_FRAME_INTERVAL_MS
            try {
                encoder.execute {
                    try { encode(image) }
                    catch (error: Throwable) { Log.w(TAG, "Frame encode failed", error) }
                    finally {
                        try { image.close() } catch (_: Throwable) {}
                        encoding.set(false)
                    }
                }
            } catch (error: Throwable) {
                try { image.close() } catch (_: Throwable) {}
                encoding.set(false)
            }
        }

        private fun encode(image: Image) {
            if (closed.get()) return
            if (!RemoteDevViewController.isAccessAllowed()) {
                latestFrame.set(null)
                return
            }
            val started = SystemClock.elapsedRealtime()
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride != 4 || rowStride < LOGICAL_WIDTH * pixelStride) {
                throw IllegalStateException("Unsupported RGBA plane pixelStride=$pixelStride rowStride=$rowStride")
            }
            val stagingWidth = rowStride / pixelStride
            val staging = stagingBitmap?.takeIf {
                !it.isRecycled && it.width == stagingWidth && it.height == LOGICAL_HEIGHT
            } ?: Bitmap.createBitmap(stagingWidth, LOGICAL_HEIGHT, Bitmap.Config.ARGB_8888).also {
                stagingBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                stagingBitmap = it
            }
            val output = outputBitmap?.takeIf {
                !it.isRecycled && it.width == STREAM_WIDTH && it.height == STREAM_HEIGHT
            } ?: Bitmap.createBitmap(STREAM_WIDTH, STREAM_HEIGHT, Bitmap.Config.ARGB_8888).also {
                outputBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                outputBitmap = it
            }

            plane.buffer.rewind()
            staging.copyPixelsFromBuffer(plane.buffer)
            output.eraseColor(Color.BLACK)
            Canvas(output).drawBitmap(
                staging,
                sourceRect,
                destinationRect,
                scalePaint,
            )

            if (isNearlyBlack(output) && latestFrame.get() != null &&
                !RemoteDevViewController.isRemoteWindowSecure()
            ) {
                droppedBlackFrames.incrementAndGet()
                return
            }

            val bytes = try {
                if (!output.compress(Bitmap.CompressFormat.JPEG, STREAM_JPEG_QUALITY, jpegBuffer)) {
                    throw IllegalStateException("Bitmap.compress returned false")
                }
                jpegBuffer.toByteArray()
            } finally {
                // Reset even after a partial/failed compression, without touching
                // the independent byte array already returned to the caller.
                jpegBuffer.reset()
            }
            latestFrame.set(
                CachedFrame(
                    sequence = frameSequence.incrementAndGet(),
                    jpeg = bytes,
                    width = STREAM_WIDTH,
                    height = STREAM_HEIGHT,
                    encodedAtElapsedMs = SystemClock.elapsedRealtime(),
                    encodeMs = SystemClock.elapsedRealtime() - started,
                    droppedBlackFrames = droppedBlackFrames.get(),
                )
            )
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            reader.setOnImageAvailableListener(null, null)
            try { virtualDisplay.release() } catch (_: Throwable) {}
            try { reader.close() } catch (_: Throwable) {}
            encoder.shutdown()
            try { encoder.awaitTermination(750, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (encoder.isTerminated) {
                stagingBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                outputBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
            thread.quitSafely()
            latestFrame.set(null)
            Log.i(TAG, "Private virtual display released")
        }
    }

    internal fun isNearlyBlack(bitmap: Bitmap): Boolean {
        var nonDark = 0
        val xStep = (bitmap.width / BLACK_SAMPLE_COLUMNS).coerceAtLeast(1)
        val yStep = (bitmap.height / BLACK_SAMPLE_ROWS).coerceAtLeast(1)
        var y = yStep / 2
        while (y < bitmap.height) {
            var x = xStep / 2
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val luma = (Color.red(color) * 54 + Color.green(color) * 183 + Color.blue(color) * 19) shr 8
                if (luma > BLACK_LUMA_THRESHOLD && ++nonDark > BLACK_NON_DARK_SAMPLE_LIMIT) {
                    return false
                }
                x += xStep
            }
            y += yStep
        }
        return true
    }
}
