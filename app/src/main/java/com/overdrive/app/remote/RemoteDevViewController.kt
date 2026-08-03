package com.overdrive.app.remote

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.inputmethod.EditorInfo
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * App-process half of Remote Overdrive Dev View.
 *
 * PixelCopy is deliberately performed against the currently resumed Overdrive
 * Activity's Window, before SurfaceFlinger combines it with BYD's opaque
 * AccAnimation layer. Input is dispatched only into app-owned view roots; this
 * class never uses global input injection and cannot address another package.
 */
object RemoteDevViewController : Application.ActivityLifecycleCallbacks {
    private const val TAG = "RemoteDevView"
    private const val UI_TIMEOUT_SECONDS = 6L
    private const val MAX_TEXT_LENGTH = 256

    private val mainHandler = Handler(Looper.getMainLooper())
    private var installed = false
    private var application: Application? = null
    private var currentActivity = WeakReference<Activity>(null)
    private var touchRoot = WeakReference<View>(null)
    private var touchDownTime = 0L

    @Synchronized
    fun install(app: Application) {
        if (installed) return
        installed = true
        application = app
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = trackIfEmpty(activity)
    override fun onActivityStarted(activity: Activity) = trackIfEmpty(activity)
    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
    }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity.get() === activity) currentActivity.clear()
    }

    private fun trackIfEmpty(activity: Activity) {
        if (currentActivity.get() == null) currentActivity = WeakReference(activity)
    }

    data class CaptureResult(
        val resultCode: Int,
        val resultName: String,
        val width: Int,
        val height: Int,
        val activityName: String?,
        val jpeg: ByteArray?,
        val detail: String? = null,
    )

    data class InputResult(
        val success: Boolean,
        val handled: Boolean,
        val activityName: String?,
        val detail: String? = null,
    )

    fun status(): InputResult {
        val activity = currentActivity.get()
        return InputResult(
            success = activity != null && !activity.isFinishing && !activity.isDestroyed,
            handled = false,
            activityName = activity?.javaClass?.name,
            detail = if (activity == null) "No Overdrive activity is ready" else null,
        )
    }

    /** Blocks the bridge worker, never the main thread. */
    fun capture(maxWidth: Int, quality: Int): CaptureResult {
        val activity = currentActivity.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            return CaptureResult(-1, "NO_ACTIVITY", 0, 0, null, null)
        }

        val resultRef = AtomicReference<CaptureResult>()
        val bitmapRef = AtomicReference<Bitmap>()
        val latch = CountDownLatch(1)
        mainHandler.post {
            val decor = activity.window.decorView
            decor.post {
                val sourceWidth = decor.width
                val sourceHeight = decor.height
                if (sourceWidth <= 0 || sourceHeight <= 0) {
                    resultRef.set(
                        CaptureResult(-1, "NO_WINDOW_SIZE", sourceWidth, sourceHeight,
                            activity.javaClass.name, null)
                    )
                    latch.countDown()
                    return@post
                }

                val outputWidth = maxWidth.coerceAtLeast(1).coerceAtMost(sourceWidth)
                val outputHeight = (sourceHeight * (outputWidth.toDouble() / sourceWidth))
                    .roundToInt().coerceAtLeast(1)
                val bitmap = try {
                    Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                } catch (error: Throwable) {
                    resultRef.set(
                        CaptureResult(-1, "BITMAP_ERROR", outputWidth, outputHeight,
                            activity.javaClass.name, null, error.javaClass.simpleName)
                    )
                    latch.countDown()
                    return@post
                }

                try {
                    PixelCopy.request(activity.window, bitmap, { code ->
                        if (code == PixelCopy.SUCCESS) {
                            drawAppOwnedOverlayRoots(bitmap, activity, sourceWidth, sourceHeight)
                            bitmapRef.set(bitmap)
                        } else {
                            bitmap.recycle()
                        }
                        resultRef.set(
                            CaptureResult(code, pixelCopyResultName(code), outputWidth,
                                outputHeight, activity.javaClass.name, null)
                        )
                        latch.countDown()
                    }, mainHandler)
                } catch (error: Throwable) {
                    bitmap.recycle()
                    resultRef.set(
                        CaptureResult(-1, "REQUEST_ERROR", outputWidth, outputHeight,
                            activity.javaClass.name, null, error.javaClass.simpleName)
                    )
                    latch.countDown()
                }
            }
        }

        if (!latch.await(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return CaptureResult(-1, "TIMEOUT", 0, 0, activity.javaClass.name, null)
        }
        val base = resultRef.get()
            ?: return CaptureResult(-1, "UNKNOWN", 0, 0, activity.javaClass.name, null)
        val bitmap = bitmapRef.get() ?: return base
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(35, 90), output)) {
                    throw IllegalStateException("Bitmap.compress returned false")
                }
                output.toByteArray()
            }
            base.copy(jpeg = bytes)
        } catch (error: Throwable) {
            base.copy(resultName = "ENCODE_ERROR", detail = error.javaClass.simpleName)
        } finally {
            bitmap.recycle()
        }
    }

    fun dispatchTouch(phase: String, normalizedX: Double, normalizedY: Double): InputResult {
        if (!normalizedX.isFinite() || !normalizedY.isFinite() ||
            normalizedX < 0.0 || normalizedX > 1.0 ||
            normalizedY < 0.0 || normalizedY > 1.0
        ) {
            return InputResult(false, false, currentActivity.get()?.javaClass?.name,
                "Coordinates must be between 0 and 1")
        }
        val action = when (phase) {
            "down" -> MotionEvent.ACTION_DOWN
            "move" -> MotionEvent.ACTION_MOVE
            "up" -> MotionEvent.ACTION_UP
            "cancel" -> MotionEvent.ACTION_CANCEL
            else -> return InputResult(false, false, currentActivity.get()?.javaClass?.name,
                "Unknown touch phase")
        }
        return runOnUiThread {
            val activity = readyActivity()
                ?: return@runOnUiThread InputResult(false, false, null, "No Overdrive activity is ready")
            val decor = activity.window.decorView
            val decorLocation = IntArray(2).also { decor.getLocationOnScreen(it) }
            val screenX = decorLocation[0] + normalizedX * decor.width
            val screenY = decorLocation[1] + normalizedY * decor.height

            val target = if (action == MotionEvent.ACTION_DOWN) {
                findTopRootAt(screenX, screenY, decor).also {
                    touchRoot = WeakReference(it)
                    touchDownTime = SystemClock.uptimeMillis()
                }
            } else {
                touchRoot.get() ?: findTopRootAt(screenX, screenY, decor)
            }
            val rootLocation = IntArray(2).also { target.getLocationOnScreen(it) }
            val eventTime = SystemClock.uptimeMillis()
            if (touchDownTime == 0L) touchDownTime = eventTime
            val event = MotionEvent.obtain(
                touchDownTime,
                eventTime,
                action,
                (screenX - rootLocation[0]).toFloat(),
                (screenY - rootLocation[1]).toFloat(),
                0,
            )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            val handled = try { target.dispatchTouchEvent(event) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchRoot.clear()
                touchDownTime = 0L
            }
            InputResult(true, handled, activity.javaClass.name)
        }
    }

    fun dispatchKey(name: String): InputResult {
        val keyCode = when (name.lowercase()) {
            "back" -> KeyEvent.KEYCODE_BACK
            "enter" -> KeyEvent.KEYCODE_ENTER
            "tab" -> KeyEvent.KEYCODE_TAB
            "escape" -> KeyEvent.KEYCODE_ESCAPE
            "delete" -> KeyEvent.KEYCODE_DEL
            "dpad_up" -> KeyEvent.KEYCODE_DPAD_UP
            "dpad_down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "dpad_left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "dpad_right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "dpad_center" -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> return InputResult(false, false, currentActivity.get()?.javaClass?.name,
                "Key is not allowed")
        }
        return runOnUiThread {
            val activity = readyActivity()
                ?: return@runOnUiThread InputResult(false, false, null, "No Overdrive activity is ready")
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                @Suppress("DEPRECATION")
                activity.onBackPressed()
                return@runOnUiThread InputResult(true, true, activity.javaClass.name)
            }
            val root = topInputRoot(activity.window.decorView)
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
            val handled = root.dispatchKeyEvent(down) or root.dispatchKeyEvent(up)
            InputResult(true, handled, activity.javaClass.name)
        }
    }

    fun dispatchText(text: String): InputResult {
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH || text.any { it == '\u0000' }) {
            return InputResult(false, false, currentActivity.get()?.javaClass?.name,
                "Text must contain 1-$MAX_TEXT_LENGTH characters")
        }
        return runOnUiThread {
            val activity = readyActivity()
                ?: return@runOnUiThread InputResult(false, false, null, "No Overdrive activity is ready")
            val root = topInputRoot(activity.window.decorView)
            val focused = root.findFocus()
            var handled = false
            if (focused != null) {
                val connection = focused.onCreateInputConnection(EditorInfo())
                if (connection != null) {
                    handled = connection.commitText(text, 1)
                }
            }
            if (!handled) {
                val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                    .getEvents(text.toCharArray())
                if (events != null) {
                    handled = events.fold(false) { any, event -> root.dispatchKeyEvent(event) || any }
                }
            }
            InputResult(true, handled, activity.javaClass.name,
                if (handled) null else "No focused text input accepted the text")
        }
    }

    private fun readyActivity(): Activity? = currentActivity.get()?.takeUnless {
        it.isFinishing || it.isDestroyed || it.window.decorView.width <= 0
    }

    private fun <T> runOnUiThread(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        mainHandler.post {
            try { result.set(block()) } catch (t: Throwable) { error.set(t) } finally { latch.countDown() }
        }
        if (!latch.await(UI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("UI dispatch timed out")
        }
        error.get()?.let { throw it }
        return result.get()
    }

    private fun findTopRootAt(screenX: Double, screenY: Double, fallback: View): View {
        val roots = appOwnedRoots()
        for (index in roots.indices.reversed()) {
            val root = roots[index]
            if (!root.isShown || root.width <= 0 || root.height <= 0) continue
            // Non-focusable overlays (for example Overdrive's recording status
            // pill) are visible but are not real input targets. Directly calling
            // dispatchTouchEvent would bypass their WindowManager flags.
            if (root !== fallback && !root.hasWindowFocus()) continue
            val location = IntArray(2).also { root.getLocationOnScreen(it) }
            if (screenX >= location[0] && screenX < location[0] + root.width &&
                screenY >= location[1] && screenY < location[1] + root.height
            ) return root
        }
        return fallback
    }

    private fun topInputRoot(fallback: View): View {
        val roots = appOwnedRoots()
        return roots.asReversed().firstOrNull { it.isShown && it.hasWindowFocus() }
            ?: roots.asReversed().firstOrNull { it.isShown }
            ?: fallback
    }

    /**
     * WindowManagerGlobal is hidden API, so this is best-effort. It lets the
     * developer view include and interact with app-owned Dialog/Popup roots.
     * A firmware that blocks reflection still gets the supported Activity
     * Window PixelCopy path and its full view hierarchy.
     */
    private fun appOwnedRoots(): List<View> {
        val packageName = application?.packageName ?: return emptyList()
        return try {
            val type = Class.forName("android.view.WindowManagerGlobal")
            val instance = type.getDeclaredMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val roots = type.getDeclaredMethod("getRootViews").invoke(instance) as? List<View>
            roots.orEmpty().filter { it.context.applicationContext.packageName == packageName }
        } catch (error: Throwable) {
            Log.d(TAG, "App-owned root enumeration unavailable: ${error.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun drawAppOwnedOverlayRoots(
        bitmap: Bitmap,
        activity: Activity,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        val activityRoot = activity.window.decorView
        val baseLocation = IntArray(2).also { activityRoot.getLocationOnScreen(it) }
        val scaleX = bitmap.width.toFloat() / sourceWidth
        val scaleY = bitmap.height.toFloat() / sourceHeight
        val canvas = Canvas(bitmap)
        appOwnedRoots().forEach { root ->
            if (root === activityRoot || !root.isShown || root.width <= 0 || root.height <= 0) return@forEach
            val location = IntArray(2).also { root.getLocationOnScreen(it) }
            canvas.save()
            canvas.scale(scaleX, scaleY)
            canvas.translate(
                (location[0] - baseLocation[0]).toFloat(),
                (location[1] - baseLocation[1]).toFloat(),
            )
            root.draw(canvas)
            canvas.restore()
        }
    }

    private fun pixelCopyResultName(code: Int): String = when (code) {
        PixelCopy.SUCCESS -> "SUCCESS"
        PixelCopy.ERROR_UNKNOWN -> "ERROR_UNKNOWN"
        PixelCopy.ERROR_TIMEOUT -> "ERROR_TIMEOUT"
        PixelCopy.ERROR_SOURCE_NO_DATA -> "ERROR_SOURCE_NO_DATA"
        PixelCopy.ERROR_SOURCE_INVALID -> "ERROR_SOURCE_INVALID"
        PixelCopy.ERROR_DESTINATION_INVALID -> "ERROR_DESTINATION_INVALID"
        else -> "ERROR_$code"
    }
}
