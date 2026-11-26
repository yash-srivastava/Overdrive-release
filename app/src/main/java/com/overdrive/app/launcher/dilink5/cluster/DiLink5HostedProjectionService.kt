package com.overdrive.app.launcher.dilink5.cluster

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.overlay.OverlayPermissionChecker
import com.overdrive.app.ui.view.AaosMapAreaCheck
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** App-UID overlay owner used only through the DUMP-protected explicit service. */
class DiLink5HostedProjectionService : Service() {
    private companion object {
        val logger by lazy { DaemonLogger.getInstance("HostedProjectionService") }
        // A release may arrive before an older CREATE, even across service instances.
        val sealionCreateFence = SealionCreateFence<IBinder>(DiLink5HostedProjectionBridge.WAIT_MS)
    }

    private data class Overlay(
        val generation: Long,
        val windowManager: WindowManager,
        val view: SurfaceView,
        val callback: SurfaceHolder.Callback,
        val bridge: IBinder,
        val sealion7Hdmi: Boolean,
    )

    private data class CreateRequest(
        val generation: Long,
        val physicalId: Int,
        val bridge: IBinder,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val bufferWidth: Int,
        val bufferHeight: Int,
        val density: Int,
        val aaosMapArea: Boolean,
        val sealion7Hdmi: Boolean,
        val deadlineMs: Long,
    ) {
        val diagnostic: String?
            get() = if (sealion7Hdmi) "Sealion 7 hosted create generation=$generation display=$physicalId" else null
    }

    private class PendingCreate(val request: CreateRequest) {
        var task: Future<*>? = null
        var bridgeDeath: IBinder.DeathRecipient? = null
    }

    private val overlays = HashMap<Long, Overlay>()
    private val pendingCreates = HashMap<Long, PendingCreate>()
    private val main = Handler(Looper.getMainLooper())
    private val createChecks = Executors.newSingleThreadExecutor()
    private var latestStartId = 0
    private var destroyed = false
    private val owner = Binder()
    private val probes = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
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
                    intent.getBooleanExtra(DiLink5HostedProjectionBridge.EXTRA_SEALION7_HDMI, false),
                )
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        pendingCreates.values.toList().forEach(::retirePendingCreate)
        createChecks.shutdownNow()
        // Finish already-submitted display probes, including a one-shot start with no overlays.
        probes.shutdown()
        overlays.keys.toList().forEach { generation ->
            release(generation, overlays[generation]?.bridge)
        }
        super.onDestroy()
    }

    private fun stopIfIdle() {
        if (!destroyed && overlays.isEmpty() && pendingCreates.isEmpty()) stopSelfResult(latestStartId)
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
        val admitted = visible && (!aaosMapArea || area != null) && (!sealion7Hdmi ||
            (!aaosMapArea && canHostSealion7Hdmi(displayId, "Sealion 7 hosted probe display=$displayId")))
        if (sealion7Hdmi) {
            val message = "Sealion 7 hosted probe display=$displayId observed=$observedId valid=$displayValid " +
                "metrics=${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi} " +
                "visible=$visible mapArea=$aaosMapArea admitted=$admitted"
            if (admitted) logger.info(message) else logger.warn(message)
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
        val sealion7Hdmi = intent.getBooleanExtra(DiLink5HostedProjectionBridge.EXTRA_SEALION7_HDMI, false)
        if (generation <= 0 || physicalId <= 0 || bridge == null || width <= 0 || height <= 0 ||
            bufferWidth <= 0 || bufferHeight <= 0 || density <= 0) {
            if (sealion7Hdmi) logger.warn("Sealion 7 hosted create rejected invalid request " +
                "generation=$generation display=$physicalId hasBridge=${bridge != null} " +
                "window=${width}x$height buffer=${bufferWidth}x$bufferHeight density=$density")
            return
        }
        val request = CreateRequest(
            generation, physicalId, bridge,
            intent.getIntExtra(DiLink5HostedProjectionBridge.EXTRA_LEFT, 0),
            intent.getIntExtra(DiLink5HostedProjectionBridge.EXTRA_TOP, 0),
            width, height, bufferWidth, bufferHeight, density,
            intent.getBooleanExtra(DiLink5HostedProjectionBridge.EXTRA_AAOS_MAP_AREA, false),
            sealion7Hdmi,
            if (sealion7Hdmi) intent.getLongExtra(DiLink5HostedProjectionBridge.EXTRA_SEALION7_DEADLINE_MS, 0) else 0,
        )
        if (overlays.containsKey(generation) || pendingCreates.containsKey(generation)) {
            request.diagnostic?.let { logger.warn("$it rejected duplicate generation") }
            return
        }
        if (sealion7Hdmi) startSealionCreate(request) else attachOverlay(request)
    }

    private fun startSealionCreate(request: CreateRequest) {
        val diagnostic = request.diagnostic
        val deadlineRejection = sealionCreateFence.rejection(
            request.bridge, request.generation, request.deadlineMs, SystemClock.elapsedRealtime(),
        )
        val rejection = when {
            deadlineRejection != null -> deadlineRejection
            request.aaosMapArea -> "conflicting map area request"
            !Sealion7HdmiProjection.matchesGeometry(
                request.left, request.top, request.width, request.height, request.bufferWidth, request.bufferHeight,
            ) -> "invalid geometry bounds=${request.left},${request.top} ${request.width}x${request.height} " +
                "buffer=${request.bufferWidth}x${request.bufferHeight}"
            else -> null
        }
        if (rejection != null) {
            logger.warn("$diagnostic rejected: $rejection")
            return
        }

        val pending = PendingCreate(request)
        pendingCreates[request.generation] = pending
        val bridgeDeath = IBinder.DeathRecipient {
            postPending(pending) { rejectPendingCreate(pending, "daemon Binder died") }
        }
        try {
            request.bridge.linkToDeath(bridgeDeath, 0)
            pending.bridgeDeath = bridgeDeath
        } catch (_: android.os.RemoteException) {
            rejectPendingCreate(pending, "daemon Binder unavailable")
            return
        }
        main.postAtTime(
            {
                val stage = if (overlays.containsKey(request.generation)) "Android surface" else "durable config"
                rejectPendingCreate(pending, "deadline expired waiting for $stage")
            },
            pending,
            SystemClock.uptimeMillis() + (request.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0),
        )
        pending.task = createChecks.submit {
            val configRejection = try {
                Sealion7HdmiProjection.createConfigRejection(request.deadlineMs)
            } catch (error: RuntimeException) {
                "config check failed: ${hostedProjectionFailure(error)}"
            }
            if (configRejection != null && !Thread.currentThread().isInterrupted) {
                logger.warn("$diagnostic config admission failed: $configRejection")
            }
            postPending(pending) { completeSealionAdmission(pending, configRejection == null) }
        }
    }

    private fun postPending(pending: PendingCreate, operation: () -> Unit) {
        main.postAtTime({ operation() }, pending, SystemClock.uptimeMillis())
    }

    private fun isCurrent(pending: PendingCreate): Boolean =
        !destroyed && pendingCreates[pending.request.generation] === pending

    private fun mayAttach(pending: PendingCreate): Boolean =
        isCurrent(pending) && pending.request.deadlineMs > SystemClock.elapsedRealtime() &&
            pending.request.bridge.isBinderAlive

    private fun completeSealionAdmission(pending: PendingCreate, configAccepted: Boolean) {
        if (!isCurrent(pending)) return
        if (!configAccepted) {
            retirePendingCreate(pending)
            stopIfIdle()
            return
        }
        if (!mayAttach(pending)) {
            rejectPendingCreate(pending, "deadline expired or daemon disconnected")
            return
        }
        val request = pending.request
        try {
            attachOverlay(request, pending)
        } catch (error: RuntimeException) {
            rejectPendingCreate(pending, "window setup failed: ${hostedProjectionFailure(error)}")
        }
        if (!overlays.containsKey(request.generation) && isCurrent(pending)) {
            retirePendingCreate(pending)
            stopIfIdle()
        }
    }

    private fun finishPendingCreate(pending: PendingCreate) {
        val request = pending.request
        if (!pendingCreates.remove(request.generation, pending)) return
        main.removeCallbacksAndMessages(pending)
        pending.task?.cancel(true)
        pending.bridgeDeath?.let {
            try { request.bridge.unlinkToDeath(it, 0) } catch (_: NoSuchElementException) {}
        }
    }

    private fun retirePendingCreate(pending: PendingCreate) {
        val request = pending.request
        sealionCreateFence.retire(request.bridge, request.generation, SystemClock.elapsedRealtime())
        finishPendingCreate(pending)
    }

    private fun rejectPendingCreate(pending: PendingCreate, reason: String) {
        if (!isCurrent(pending)) return
        val request = pending.request
        logger.warn("${request.diagnostic} rejected: $reason")
        retirePendingCreate(pending)
        if (overlays.containsKey(request.generation)) release(request.generation, request.bridge, true)
        stopIfIdle()
    }

    private fun attachOverlay(request: CreateRequest, pending: PendingCreate? = null) {
        val (generation, physicalId, bridge, left, top, width, height, bufferWidth, bufferHeight, density,
            aaosMapArea, sealion7Hdmi) = request
        val diagnostic = request.diagnostic
        val display = if (sealion7Hdmi) {
            if (!OverlayPermissionChecker.isGranted(this)) {
                logger.warn("$diagnostic rejected overlay permission denied")
                return
            }
            Sealion7HdmiProjection.findPhysicalDisplay(this)?.takeIf { it.displayId == physicalId }
        } else {
            (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(physicalId)?.takeIf { it.isValid }
        }
        if (display == null) {
            diagnostic?.let { logger.warn("$it rejected physical display unavailable or ineligible") }
            return
        }
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
            ?: run {
                diagnostic?.let { logger.warn("$it rejected window manager unavailable") }
                return
            }
        val view = SurfaceView(windowContext)
        view.holder.setFixedSize(bufferWidth, bufferHeight)
        var reportedWindowMatch: Boolean? = null
        fun sealion7WindowMatches(): Boolean {
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            val currentDisplay = view.display
            val displayMatches = currentDisplay != null && currentDisplay.displayId == physicalId &&
                AaosMapAreaCheck.isTarget(this, currentDisplay)
            val matches = displayMatches &&
                Sealion7HdmiProjection.matchesGeometry(position[0], position[1], view.width, view.height,
                    bufferWidth, bufferHeight)
            // Report the first layout and the first later mismatch, even if relayout repeats.
            if (reportedWindowMatch == null || reportedWindowMatch == true && !matches) {
                reportedWindowMatch = matches
                val message = "$diagnostic layout matches=$matches actualDisplay=${currentDisplay?.displayId} " +
                    "displayMatches=$displayMatches bounds=${position[0]},${position[1]} ${view.width}x${view.height} " +
                    "expectedBounds=$left,$top ${width}x$height"
                if (matches) logger.info(message) else logger.warn(message)
            }
            return matches
        }
        val callback = object : SurfaceHolder.Callback {
            private var reportedSurface = false

            override fun surfaceCreated(holder: SurfaceHolder) {
                if (diagnostic != null) {
                    // A surface alone does not establish where WindowManager put the overlay.
                    view.post {
                        if (overlays[generation]?.view !== view) return@post
                        if (pending == null || !isCurrent(pending)) return@post
                        if (!mayAttach(pending)) {
                            rejectPendingCreate(pending, "surface arrived after deadline or daemon disconnect")
                            return@post
                        }
                        val windowMatches = sealion7WindowMatches()
                        val handoffDiagnostic = diagnostic.takeUnless { reportedSurface }
                        val surfaceValid = holder.surface.isValid
                        if (handoffDiagnostic != null) {
                            reportedSurface = true
                            val frame = holder.surfaceFrame
                            val message = "$handoffDiagnostic surface valid=$surfaceValid " +
                                "buffer=${frame.width()}x${frame.height()} expectedBuffer=${bufferWidth}x$bufferHeight"
                            if (surfaceValid) logger.info(message) else logger.warn(message)
                        }
                        if (windowMatches && surfaceValid) publishSurface(holder, handoffDiagnostic)
                        else release(generation, bridge)
                    }
                } else publishSurface(holder)
            }

            private fun publishSurface(holder: SurfaceHolder, handoffDiagnostic: String? = null) {
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
                        handoffDiagnostic?.let { logger.warn("$it surface Binder transaction unhandled") }
                        release(generation, bridge)
                        return
                    }
                    reply.readException()
                    val accepted = reply.readInt() == 1
                    val contentDisplayId = if (accepted) reply.readInt() else null
                    if (contentDisplayId == null || contentDisplayId <= 0) {
                        handoffDiagnostic?.let {
                            logger.warn("$it surface Binder rejected accepted=$accepted contentDisplay=$contentDisplayId")
                        }
                        release(generation, bridge)
                    } else {
                        if (pending != null) finishPendingCreate(pending)
                        handoffDiagnostic?.let { logger.info("$it surface Binder accepted contentDisplay=$contentDisplayId") }
                    }
                } catch (error: Throwable) {
                    handoffDiagnostic?.let { logger.warn("$it surface Binder handoff failed ${hostedProjectionFailure(error)}") }
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
        if (pending != null && !mayAttach(pending)) {
            rejectPendingCreate(pending, "window setup exceeded deadline or daemon disconnected")
            return
        }
        overlays[generation] = Overlay(generation, windowManager, view, callback, bridge, sealion7Hdmi)
        try {
            windowManager.addView(view, params)
        } catch (error: Throwable) {
            diagnostic?.let { logger.warn("$it addView failed ${hostedProjectionFailure(error)}") }
            release(generation, bridge)
        }
    }

    private fun canHostSealion7Hdmi(displayId: Int, diagnostic: String): Boolean {
        val overlayGranted = OverlayPermissionChecker.isGranted(this)
        val eligibleId = if (overlayGranted) Sealion7HdmiProjection.findDisplay(this)?.displayId else null
        val admitted = overlayGranted && eligibleId == displayId
        val message = "$diagnostic overlayGranted=$overlayGranted " +
            "eligibleDisplay=${eligibleId ?: if (overlayGranted) "unavailable" else "unchecked"} admitted=$admitted"
        if (admitted) logger.info(message) else logger.warn(message)
        return admitted
    }

    private fun release(
        generation: Long,
        bridgeOverride: IBinder? = null,
        sealion7Hdmi: Boolean = false,
    ): Boolean {
        val overlay = overlays[generation]
        val pending = pendingCreates[generation]
        val isSealion = sealion7Hdmi || overlay?.sealion7Hdmi == true || pending != null
        if (isSealion && generation > 0) {
            val liveBridge = pending?.request?.bridge ?: overlay?.bridge
            val now = SystemClock.elapsedRealtime()
            bridgeOverride?.let { sealionCreateFence.retire(it, generation, now) }
            if (liveBridge != null) {
                // An old daemon's delayed RELEASE must not cancel a newer live CREATE.
                if (bridgeOverride != null && bridgeOverride != liveBridge && liveBridge.isBinderAlive) return false
                sealionCreateFence.retire(liveBridge, generation, now)
            }
            pending?.let(::finishPendingCreate)
        }
        // A restarted daemon can reclaim a Sealion overlay whose original bridge died.
        // Only an explicit release delivered through the protected service supplies a new bridge.
        val bridge = if (overlay == null ||
            (sealion7Hdmi && overlay.sealion7Hdmi && !overlay.bridge.isBinderAlive)
        ) {
            bridgeOverride
        } else {
            overlay.bridge
        }
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
        if (isSealion) stopIfIdle()
        else if (overlays.isEmpty() && pendingCreates.isEmpty()) stopSelf()
        return overlayReleased && daemonReleased
    }
}

/** Main-thread fence kept across service restarts for the lifetime of an in-flight CREATE. */
internal class SealionCreateFence<Owner : Any>(private val requestWindowMs: Long) {
    private val retired = HashMap<Pair<Owner, Long>, Long>()

    fun rejection(owner: Owner, generation: Long, deadlineMs: Long, nowMs: Long): String? {
        discardExpired(nowMs)
        return when {
            deadlineMs <= nowMs || deadlineMs - nowMs > requestWindowMs -> "invalid or expired deadline"
            (owner to generation) in retired -> "generation already released"
            else -> null
        }
    }

    fun retire(owner: Owner, generation: Long, nowMs: Long) {
        discardExpired(nowMs)
        retired[owner to generation] = nowMs + requestWindowMs
    }

    private fun discardExpired(nowMs: Long) {
        retired.entries.removeAll { it.value <= nowMs }
    }
}
