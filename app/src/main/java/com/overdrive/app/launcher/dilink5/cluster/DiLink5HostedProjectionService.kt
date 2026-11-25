package com.overdrive.app.launcher.dilink5.cluster

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ResultReceiver
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.overlay.OverlayPermissionChecker
import com.overdrive.app.ui.view.AaosMapAreaCheck
import java.util.concurrent.Executors

/** App-UID overlay owner used only through the DUMP-protected explicit service. */
class DiLink5HostedProjectionService : Service() {
    private companion object {
        val logger by lazy { DaemonLogger.getInstance("HostedProjectionService") }
    }

    private data class Overlay(
        val generation: Long,
        val windowManager: WindowManager,
        val view: SurfaceView,
        val callback: SurfaceHolder.Callback,
        val bridge: IBinder,
    )

    private val overlays = HashMap<Long, Overlay>()
    private val owner = Binder()
    private val probes = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DiLink5HostedProjectionBridge.ACTION_PROBE_DISPLAY -> {
                if (intent.getBooleanExtra(DiLink5HostedProjectionBridge.EXTRA_SEALION7_HDMI, false)) {
                    val displayId = intent.getIntExtra(DiLink5HostedProjectionBridge.EXTRA_PHYSICAL_DISPLAY, -1)
                    logger.info("Sealion 7 hosted probe received display=$displayId")
                }
                probes.execute { probeDisplay(intent) }
            }
            DiLink5HostedProjectionBridge.ACTION_CREATE -> create(intent)
            DiLink5HostedProjectionBridge.ACTION_RELEASE ->
                release(
                    intent.getLongExtra(DiLink5HostedProjectionBridge.EXTRA_GENERATION, -1L),
                    intent.extras?.getBinder(DiLink5HostedProjectionBridge.EXTRA_BRIDGE),
                )
        }
        if (overlays.isEmpty()) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Finish already-submitted display probes, including a one-shot start with no overlays.
        probes.shutdown()
        overlays.keys.toList().forEach { generation ->
            release(generation, overlays[generation]?.bridge)
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun probeDisplay(intent: Intent) {
        val displayId = intent.getIntExtra(
            DiLink5HostedProjectionBridge.EXTRA_PHYSICAL_DISPLAY,
            -1,
        )
        val sealion7Hdmi = intent.getBooleanExtra(DiLink5HostedProjectionBridge.EXTRA_SEALION7_HDMI, false)
        val receiver = intent.getParcelableExtra<ResultReceiver>(
            DiLink5HostedProjectionBridge.EXTRA_RESULT_RECEIVER,
        ) ?: run {
            if (sealion7Hdmi) logger.warn("Sealion 7 hosted probe display=$displayId missing result receiver")
            return
        }
        val display = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(displayId)
        val metrics = android.util.DisplayMetrics()
        if (display != null && display.isValid) {
            try {
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
            } catch (error: Throwable) {
                if (sealion7Hdmi) logger.warn("Sealion 7 hosted probe display=$displayId metrics failed ${hostedProjectionFailure(error)}")
            }
        }
        val observedId = display?.displayId ?: -1
        val displayValid = display?.isValid == true
        val visible = HostedProjectionBoundary.appDisplayVisible(
            display != null,
            displayValid,
            observedId,
            displayId,
        ) && HostedProjectionBoundary.appDisplayInfoValid(
            observedId,
            displayId,
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
        )
        val aaosMapArea = intent.getBooleanExtra(DiLink5HostedProjectionBridge.EXTRA_AAOS_MAP_AREA, false)
        val area = if (aaosMapArea && visible && display != null) AaosMapAreaCheck.confirmedBounds(this, display) else null
        val admitted = visible && (!aaosMapArea || area != null) &&
            (!sealion7Hdmi || (!aaosMapArea && canHostSealion7Hdmi(displayId, logProbe = true)))
        if (sealion7Hdmi) {
            logger.info("Sealion 7 hosted probe display=$displayId observed=$observedId valid=$displayValid " +
                "metrics=${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi} " +
                "visible=$visible mapArea=$aaosMapArea admitted=$admitted")
        }
        receiver.send(if (admitted) 1 else 0, if (admitted) android.os.Bundle().apply {
            putInt(DiLink5HostedProjectionBridge.RESULT_DISPLAY_ID, displayId)
            putInt(DiLink5HostedProjectionBridge.RESULT_DISPLAY_WIDTH, metrics.widthPixels)
            putInt(DiLink5HostedProjectionBridge.RESULT_DISPLAY_HEIGHT, metrics.heightPixels)
            putInt(DiLink5HostedProjectionBridge.RESULT_DISPLAY_DENSITY, metrics.densityDpi)
            if (area != null) putIntArray(DiLink5HostedProjectionBridge.RESULT_CONFIRMED_MAP_BOUNDS,
                intArrayOf(area.left, area.top, area.right, area.bottom))
        } else null)
    }

    private fun create(intent: Intent) {
        val generation = intent.getLongExtra(DiLink5HostedProjectionBridge.EXTRA_GENERATION, -1L)
        val physicalId = intent.getIntExtra(
            DiLink5HostedProjectionBridge.EXTRA_PHYSICAL_DISPLAY, -1)
        val bridge = intent.extras?.getBinder(DiLink5HostedProjectionBridge.EXTRA_BRIDGE)
        val width = intent.getIntExtra(DiLink5HostedProjectionBridge.EXTRA_WIDTH, 0)
        val height = intent.getIntExtra(DiLink5HostedProjectionBridge.EXTRA_HEIGHT, 0)
        val bufferWidth = intent.getIntExtra(
            DiLink5HostedProjectionBridge.EXTRA_BUFFER_WIDTH, 0)
        val bufferHeight = intent.getIntExtra(
            DiLink5HostedProjectionBridge.EXTRA_BUFFER_HEIGHT, 0)
        val density = intent.getIntExtra(DiLink5HostedProjectionBridge.EXTRA_DENSITY, 0)
        if (generation <= 0 || physicalId <= 0 || bridge == null || width <= 0 || height <= 0 ||
            bufferWidth <= 0 || bufferHeight <= 0 || density <= 0 || overlays.containsKey(generation)) {
            return
        }
        val manager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val display = manager.getDisplay(physicalId)?.takeIf { it.isValid } ?: return
        val aaosMapArea = intent.getBooleanExtra(DiLink5HostedProjectionBridge.EXTRA_AAOS_MAP_AREA, false)
        val sealion7Hdmi = intent.getBooleanExtra(DiLink5HostedProjectionBridge.EXTRA_SEALION7_HDMI, false)
        val left = intent.getIntExtra(DiLink5HostedProjectionBridge.EXTRA_LEFT, 0)
        val top = intent.getIntExtra(DiLink5HostedProjectionBridge.EXTRA_TOP, 0)
        if (sealion7Hdmi && (aaosMapArea || !canHostSealion7Hdmi(physicalId) ||
                !Sealion7HdmiProjection.matchesGeometry(left, top, width, height, bufferWidth, bufferHeight))) return
        val area = if (aaosMapArea) {
            AaosMapAreaCheck.confirmedBounds(this, display)?.takeIf {
                it.left == left && it.top == top && it.width() == width && it.height() == height &&
                    it.width() == bufferWidth && it.height() == bufferHeight
            } ?: return
        } else null
        val displayContext = createDisplayContext(display)
        // The marker and the hosted surface must get their insets and window token from the same display.
        val windowContext = if (aaosMapArea) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else displayContext
        val windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return
        val view = SurfaceView(windowContext)
        view.holder.setFixedSize(bufferWidth, bufferHeight)
        fun sealion7WindowMatches(): Boolean {
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            val currentDisplay = view.display ?: return false
            return currentDisplay.displayId == physicalId && AaosMapAreaCheck.isTarget(this, currentDisplay) &&
                Sealion7HdmiProjection.matchesGeometry(position[0], position[1], view.width, view.height,
                    bufferWidth, bufferHeight)
        }
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (sealion7Hdmi) {
                    // A surface alone does not establish where WindowManager put the overlay.
                    view.post {
                        if (overlays[generation]?.view !== view) return@post
                        if (sealion7WindowMatches() && holder.surface.isValid) publishSurface(holder)
                        else release(generation, bridge)
                    }
                } else publishSurface(holder)
            }

            private fun publishSurface(holder: SurfaceHolder) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DiLink5HostedProjectionBridge.DESCRIPTOR)
                    data.writeInt(DiLink5HostedProjectionBridge.PROTOCOL_VERSION)
                    data.writeLong(generation)
                    data.writeInt(physicalId)
                    data.writeInt(bufferWidth)
                    data.writeInt(bufferHeight)
                    data.writeInt(density)
                    data.writeStrongBinder(owner)
                    data.writeInt(1)
                    holder.surface.writeToParcel(data, 0)
                    if (!bridge.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) {
                        release(generation, bridge)
                        return
                    }
                    reply.readException()
                    if (reply.readInt() != 1 || reply.readInt() <= 0) {
                        release(generation, bridge)
                    }
                } catch (_: Throwable) {
                    release(generation, bridge)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                release(generation, bridge)
            }
        }
        view.holder.addCallback(callback)
        val params = when {
            area != null -> AaosMapAreaCheck.windowParams(area)
            sealion7Hdmi -> WindowManager.LayoutParams(
                width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.OPAQUE,
            ).apply {
                @Suppress("RtlHardcoded") // Physical HDMI coordinates are independent of locale.
                gravity = Gravity.TOP or Gravity.LEFT
            }
            else -> WindowManager.LayoutParams(width, height, 2038, 264, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = left
                y = top
            }
        }
        params.title = "OverDrive cluster projection"
        if (sealion7Hdmi) {
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (!sealion7WindowMatches()) {
                    view.visibility = View.INVISIBLE
                    view.post { if (overlays[generation]?.view === view) release(generation, bridge) }
                }
            }
        } else if (area != null) {
            // Reject a platform relayout (insets, clipping, display replacement) that no
            // longer puts these pixels inside the exact physical frame checked while parked.
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                if (position[0] != left || position[1] != top || view.width != width ||
                    view.height != height || AaosMapAreaCheck.confirmedBounds(this, display) != area) {
                    view.visibility = android.view.View.INVISIBLE
                    view.post { release(generation, bridge) }
                }
            }
        }
        overlays[generation] = Overlay(generation, windowManager, view, callback, bridge)
        try {
            windowManager.addView(view, params)
        } catch (error: Throwable) {
            if (sealion7Hdmi) {
                logger.warn("Sealion 7 hosted create generation=$generation display=$physicalId " +
                    "addView failed ${hostedProjectionFailure(error)}")
            }
            release(generation, bridge)
        }
    }

    private fun canHostSealion7Hdmi(displayId: Int, logProbe: Boolean = false): Boolean {
        val overlayGranted = OverlayPermissionChecker.isGranted(this)
        val eligibleId = if (overlayGranted) Sealion7HdmiProjection.findDisplay(this)?.displayId else null
        if (logProbe) {
            logger.info("Sealion 7 hosted probe display=$displayId overlayGranted=$overlayGranted " +
                "eligibleDisplay=${eligibleId ?: if (overlayGranted) "unavailable" else "unchecked"}")
        }
        return overlayGranted && eligibleId == displayId
    }

    private fun release(generation: Long, bridgeOverride: IBinder? = null): Boolean {
        val overlay = overlays[generation]
        val bridge = overlay?.bridge ?: bridgeOverride
        val overlayReleased = RetriableHostedResources.release(overlays, generation) { current ->
            try { current.view.holder.removeCallback(current.callback) } catch (_: Throwable) {}
            try {
                current.windowManager.removeViewImmediate(current.view)
                true
            } catch (_: Throwable) {
                !current.view.isAttachedToWindow && current.view.parent == null
            }
        }

        var daemonReleased = false
        if (bridge != null) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DiLink5HostedProjectionBridge.DESCRIPTOR)
                data.writeInt(DiLink5HostedProjectionBridge.PROTOCOL_VERSION)
                data.writeLong(generation)
                data.writeInt(if (overlayReleased) 1 else 0)
                daemonReleased = bridge.transact(
                    DiLink5HostedProjectionBridge.TX_RELEASE_FROM_APP,
                    data,
                    reply,
                    0,
                )
                if (daemonReleased) {
                    reply.readException()
                    daemonReleased = reply.readInt() == 1
                }
            } catch (_: Throwable) {
                daemonReleased = false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        if (overlays.isEmpty()) stopSelf()
        return overlayReleased && daemonReleased
    }
}
