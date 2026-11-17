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
import android.view.WindowManager

/** App-UID overlay owner used only through the DUMP-protected explicit service. */
class DiLink5HostedProjectionService : Service() {
    private data class Overlay(
        val generation: Long,
        val windowManager: WindowManager,
        val view: SurfaceView,
        val callback: SurfaceHolder.Callback,
        val bridge: IBinder,
    )

    private val overlays = HashMap<Long, Overlay>()
    private val owner = Binder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DiLink5HostedProjectionBridge.ACTION_PROBE_DISPLAY -> probeDisplay(intent)
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
        val receiver = intent.getParcelableExtra<ResultReceiver>(
            DiLink5HostedProjectionBridge.EXTRA_RESULT_RECEIVER,
        ) ?: return
        val display = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(displayId)
        val metrics = android.util.DisplayMetrics()
        if (display != null && display.isValid) {
            try {
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
            } catch (_: Throwable) {
            }
        }
        val visible = HostedProjectionBoundary.appDisplayVisible(
            display != null,
            display?.isValid == true,
            display?.displayId ?: -1,
            displayId,
        ) && HostedProjectionBoundary.appDisplayInfoValid(
            display?.displayId ?: -1,
            displayId,
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
        )
        receiver.send(if (visible) 1 else 0, if (visible) android.os.Bundle().apply {
            putInt(DiLink5HostedProjectionBridge.RESULT_DISPLAY_ID, displayId)
            putInt(DiLink5HostedProjectionBridge.RESULT_DISPLAY_WIDTH, metrics.widthPixels)
            putInt(DiLink5HostedProjectionBridge.RESULT_DISPLAY_HEIGHT, metrics.heightPixels)
            putInt(DiLink5HostedProjectionBridge.RESULT_DISPLAY_DENSITY, metrics.densityDpi)
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
        val displayContext = createDisplayContext(display)
        val windowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return
        val view = SurfaceView(displayContext)
        view.holder.setFixedSize(bufferWidth, bufferHeight)
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
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
        val params = WindowManager.LayoutParams(
            width,
            height,
            2038,
            264,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = intent.getIntExtra(DiLink5HostedProjectionBridge.EXTRA_LEFT, 0)
            y = intent.getIntExtra(DiLink5HostedProjectionBridge.EXTRA_TOP, 0)
            title = "OverDrive cluster projection"
        }
        overlays[generation] = Overlay(generation, windowManager, view, callback, bridge)
        try {
            windowManager.addView(view, params)
        } catch (_: Throwable) {
            release(generation, bridge)
        }
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
