package com.overdrive.app.ui.view

import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors

/**
 * App-process ([uid 1000]) client for the daemon's cluster-view mirror service.
 *
 * Hands the Projection screen's [Surface] (from its `TextureView`) to the daemon over a
 * Binder transaction so SurfaceFlinger can composite the driver cluster INTO that view. HTTP
 * (the normal app↔daemon channel) cannot carry a live `Surface` — only a kernel Binder can —
 * so this is the one path that goes direct to the daemon-registered service. Everything else
 * (open/close cast, status polling) stays on HTTP.
 *
 * The daemon service is resolved by name from `ServiceManager`; if it isn't up yet (daemon
 * still starting) every call is a graceful no-op returning [STATE_STOPPED] and the caller
 * retries on the next surface/size event.
 */
object ClusterViewMirrorClient {

    private const val TAG = "ClusterViewMirror"

    // MUST match ClusterViewMirrorService.
    private const val SERVICE_NAME = "overdrive_cluster_view"
    private const val DESCRIPTOR = "com.overdrive.app.cluster.IClusterViewMirror"
    const val ACTION_PUBLISH = "com.overdrive.app.action.CLUSTER_VIEW_MIRROR"
    const val EXTRA_BINDER = "cluster_view_mirror"
    private const val FIRST = IBinder.FIRST_CALL_TRANSACTION
    private const val TXN_ATTACH = FIRST
    private const val TXN_REPROJECT = FIRST + 1
    private const val TXN_DETACH = FIRST + 2
    private const val TXN_INPUT_CAPABILITIES = FIRST + 4
    private const val TXN_TOUCH = FIRST + 5
    private const val TXN_KEY = FIRST + 6
    private const val TXN_INPUT_CANCEL = FIRST + 7

    const val STATE_STOPPED = 0
    const val STATE_ACTIVE = 1
    const val STATE_NO_PROJECTION = 2
    const val STATE_UNSUPPORTED = 3
    // App-side only: the daemon mirror service couldn't be resolved from ServiceManager
    // (daemon still starting, an OLD daemon from before an in-place update that never
    // registered it, or addService unsupported on this firmware). Distinct so the UI can
    // tell the user to restart the app/daemon rather than showing a generic failure.
    const val STATE_SERVICE_DOWN = 5

    const val SCALE_FIT = 0
    const val SCALE_FILL = 1
    const val SCALE_ZOOM = 2

    @Volatile private var cached: IBinder? = null
    @Volatile private var cachedDeathRecipient: IBinder.DeathRecipient? = null
    // Delivered by the daemon's broadcast when ServiceManager refuses its registration (DI5).
    @Volatile private var delivered: IBinder? = null
    private val nextSessionId = AtomicLong(1L)
    private val newestSessionId = AtomicLong(0L)
    @Volatile private var activeSessionId = 0L
    // The daemon links this process-owned token to death. If the UI crashes or is killed while
    // a Surface is attached, it can tear down the mirror instead of retaining a dead producer.
    // The cast itself has an independent daemon-owned lifecycle and is not owned by this token.
    private val ownerToken = Binder()
    private val nextInputSequence = AtomicLong(1L)
    private val inputEpoch = AtomicLong(1L)
    @Volatile private var inputCapabilitiesBinder: IBinder? = null
    @Volatile private var streamingSupported = false
    @Volatile private var sourceLayoutGeneration = 0L
    private val touchWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ClusterMirrorTouch").apply { isDaemon = true }
    }
    private val touchDispatcher = ClusterTouchDispatcher(
        touchWorker, { SystemClock.uptimeMillis() },
        object : ClusterTouchDispatcher.Sender {
            override fun send(frame: ClusterTouchDispatcher.Frame): Boolean =
                synchronized(this@ClusterViewMirrorClient) {
                    if (!isSessionActive(frame.sessionId) || frame.inputEpoch != inputEpoch.get()
                        || frame.layoutGeneration != sourceLayoutGeneration) {
                        return@synchronized false
                    }
                    inputTransaction(frame.sessionId, TXN_TOUCH) { data ->
                        data.writeLong(frame.eventTime)
                        data.writeInt(frame.action)
                        data.writeInt(frame.actionIndex)
                        data.writeInt(frame.ids.size)
                        for (i in frame.ids.indices) {
                            data.writeInt(frame.ids[i])
                            data.writeFloat(frame.xs[i]); data.writeFloat(frame.ys[i])
                        }
                        data.writeLong(frame.layoutGeneration)
                    }
                }

            override fun cancel(sessionId: Long) {
                synchronized(this@ClusterViewMirrorClient) {
                    if (isSessionActive(sessionId)) inputTransaction(sessionId, TXN_INPUT_CANCEL) {}
                }
            }
        }, 16, 250L
    )

    /**
     * UI-side capability read only. The probe happens on the mirror attach worker, before
     * any streaming DOWN. No Binder transaction or monitor wait runs in a touch callback.
     */
    fun supportsTouchStream(sessionId: Long): Boolean =
        isSessionActive(sessionId) && streamingSupported && inputCapabilitiesBinder === cached

    fun touch(
        sessionId: Long, event: MotionEvent, viewW: Int, viewH: Int,
        surfaceX: Int, surfaceY: Int, surfaceW: Int, surfaceH: Int
    ): Boolean {
        val epoch = inputEpoch.get()
        if (!isSessionActive(sessionId) || viewW <= 0 || viewH <= 0
            || surfaceW <= 0 || surfaceH <= 0 || event.pointerCount !in 1..10) return false
        // Copy primitive pointer data now. The worker never retains a framework
        // MotionEvent, which Android recycles as soon as this callback returns.
        val ids = IntArray(event.pointerCount)
        val xs = FloatArray(event.pointerCount)
        val ys = FloatArray(event.pointerCount)
        for (i in ids.indices) {
            ids[i] = event.getPointerId(i)
            xs[i] = surfaceX + event.getX(i) * surfaceW / viewW
            ys[i] = surfaceY + event.getY(i) * surfaceH / viewH
        }
        if (inputEpoch.get() != epoch) return false
        return touchDispatcher.offer(ClusterTouchDispatcher.Frame(
            sessionId, event.eventTime, event.actionMasked, event.actionIndex, ids, xs, ys,
            epoch, sourceLayoutGeneration))
    }

    fun cancelTouch(sessionId: Long) {
        if (activeSessionId == sessionId) inputEpoch.incrementAndGet()
        touchDispatcher.cancel(sessionId)
    }

    /** Capture on the UI thread with a key click; a later worker cannot target a
     *  reattached mirror just because that Fragment retained the same session id. */
    fun currentInputEpoch(): Long = inputEpoch.get()

    /** Called by the attach worker. Defensive asynchronous fallback covers any future
     * caller that accidentally attaches on the UI thread. */
    private fun warmInputCapabilities(sessionId: Long, svc: IBinder) {
        val probe = {
            synchronized(this) {
                if (activeSessionId == sessionId && cached === svc) {
                    val supported = inputTransaction(
                        sessionId, TXN_INPUT_CAPABILITIES, numbered = false,
                        readExtra = { reply ->
                            sourceLayoutGeneration = if (reply.dataAvail() >= 8) reply.readLong() else 0L
                        }) {}
                    if (activeSessionId == sessionId && cached === svc) {
                        inputCapabilitiesBinder = svc
                        streamingSupported = supported
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) touchWorker.execute(probe)
        else probe()
    }

    @Synchronized
    fun key(
        sessionId: Long, keyCode: Int, eventTime: Long = SystemClock.uptimeMillis(),
        expectedEpoch: Long = inputEpoch.get()
    ): Boolean =
        expectedEpoch == inputEpoch.get() && isSessionActive(sessionId)
            && inputTransaction(sessionId, TXN_KEY) {
            it.writeLong(eventTime)
            it.writeInt(keyCode)
            it.writeLong(sourceLayoutGeneration)
        }

    private fun inputTransaction(
        sessionId: Long, code: Int, numbered: Boolean = true,
        readExtra: ((Parcel) -> Unit)? = null, write: (Parcel) -> Unit
    ): Boolean {
        val svc = service() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            writeSessionIdentity(data, sessionId)
            if (numbered) data.writeLong(nextInputSequence.getAndIncrement())
            write(data)
            if (!svc.transact(code, data, reply, 0)) return false
            reply.readException()
            val accepted = reply.readInt() == 1
            readExtra?.invoke(reply)
            accepted
        } catch (t: Throwable) {
            Log.w(TAG, "cluster input transaction failed: ${t.message}")
            // A reply can fail after DOWN was accepted. Cancel on the same Binder before
            // discarding it; never replay that uncertain gesture through the HTTP fallback.
            if (code == TXN_TOUCH || code == TXN_KEY) {
                val cleanup = Parcel.obtain()
                try {
                    cleanup.writeInterfaceToken(DESCRIPTOR)
                    writeSessionIdentity(cleanup, sessionId)
                    cleanup.writeLong(nextInputSequence.getAndIncrement())
                    svc.transact(TXN_INPUT_CANCEL, cleanup, null, IBinder.FLAG_ONEWAY)
                } catch (_: Throwable) {
                    // Owner death/mirror teardown provides the final cleanup path.
                } finally {
                    cleanup.recycle()
                }
            }
            invalidateService(svc)
            false
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    fun newSessionId(): Long {
        val id = nextSessionId.getAndIncrement()
        while (true) {
            val seen = newestSessionId.get()
            if (id <= seen || newestSessionId.compareAndSet(seen, id)) break
        }
        return id
    }

    /** Accept the daemon's broadcast Binder; it is validated by descriptor before first use. */
    fun offerBinder(binder: IBinder?) {
        if (binder != null) delivered = binder
    }

    /** True while [sessionId] is attached on a live service (cleared when the daemon dies). */
    fun isSessionActive(sessionId: Long): Boolean {
        val c = cached
        return activeSessionId == sessionId && c != null && c.isBinderAlive
    }

    private fun deliveredService(): IBinder? {
        val b = delivered ?: return null
        val valid = try {
            b.isBinderAlive && b.interfaceDescriptor == DESCRIPTOR
        } catch (_: Throwable) {
            false
        }
        if (!valid && delivered === b) delivered = null
        return if (valid) b else null
    }

    private fun service(): IBinder? {
        val c = cached
        if (c != null && c.isBinderAlive) return c
        if (c != null) invalidateService(c)
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val get = sm.getMethod("getService", String::class.java)
            val b = get.invoke(null, SERVICE_NAME) as? IBinder
                ?: deliveredService()
                ?: return null
            val death = IBinder.DeathRecipient { serviceDied(b) }
            try {
                b.linkToDeath(death, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "mirror service died while caching: ${t.message}")
                return null
            }
            cached = b
            cachedDeathRecipient = death
            b
        } catch (t: Throwable) {
            Log.w(TAG, "getService failed: ${t.message}")
            null
        }
    }

    private fun serviceDied(dead: IBinder) {
        synchronized(this) {
            if (delivered === dead) delivered = null
            if (cached !== dead) return
            Log.w(TAG, "mirror service binder died")
            cached = null
            cachedDeathRecipient = null
            activeSessionId = 0L
            inputCapabilitiesBinder = null
            streamingSupported = false
        }
    }

    private fun invalidateService(expected: IBinder) {
        synchronized(this) {
            val current = cached
            if (current !== expected) return
            cachedDeathRecipient?.let { death ->
                try { current.unlinkToDeath(death, 0) } catch (_: Throwable) {}
            }
            cached = null
            cachedDeathRecipient = null
            activeSessionId = 0L
            inputCapabilitiesBinder = null
            streamingSupported = false
        }
    }

    /**
     * Additive protocol trailer. Daemons predating session support ignore trailing Parcel
     * data; updated daemons use both values to reject delayed calls from an old Fragment or
     * app process without changing the stable transaction numbers or leading wire fields.
     */
    private fun writeSessionIdentity(data: Parcel, sessionId: Long) {
        data.writeStrongBinder(ownerToken)
        data.writeLong(sessionId)
    }

    /** Hand [surface] + the pane [box] (surface px) to the daemon; it (re)creates the mirror
     *  projecting the cluster into that box per [mode]. Returns a STATE_* code. */
    @Synchronized
    fun attach(
        sessionId: Long,
        surface: Surface?,
        bx: Int,
        by: Int,
        bw: Int,
        bh: Int,
        mode: Int
    ): Int {
        if (surface == null || !surface.isValid || bw <= 0 || bh <= 0) return STATE_STOPPED
        if (sessionId != newestSessionId.get()) return STATE_STOPPED
        streamingSupported = false
        inputCapabilitiesBinder = null
        inputEpoch.incrementAndGet()
        touchDispatcher.cancel(sessionId)
        val svc = service() ?: return STATE_SERVICE_DOWN
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(mode)
            data.writeInt(bx); data.writeInt(by); data.writeInt(bw); data.writeInt(bh)
            data.writeInt(1)
            surface.writeToParcel(data, 0)
            writeSessionIdentity(data, sessionId)
            check(svc.transact(TXN_ATTACH, data, reply, 0)) {
                "attach transaction not handled"
            }
            reply.readException()
            reply.readInt().also { state ->
                activeSessionId = if (state == STATE_ACTIVE) sessionId else 0L
                inputCapabilitiesBinder = null
                streamingSupported = false
                if (state == STATE_ACTIVE) warmInputCapabilities(sessionId, svc)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "attach transact failed: ${t.message}")
            invalidateService(svc)
            STATE_SERVICE_DOWN
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /** Re-project the LIVE mirror into a new pane [box] (resize fast path — no teardown). */
    @Synchronized
    fun reproject(sessionId: Long, bx: Int, by: Int, bw: Int, bh: Int, mode: Int): Int {
        if (bw <= 0 || bh <= 0) return STATE_STOPPED
        if (sessionId != newestSessionId.get() || activeSessionId != sessionId) {
            return STATE_STOPPED
        }
        inputEpoch.incrementAndGet()
        touchDispatcher.cancel(sessionId)
        val svc = service() ?: return STATE_SERVICE_DOWN
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(mode)
            data.writeInt(bx); data.writeInt(by); data.writeInt(bw); data.writeInt(bh)
            writeSessionIdentity(data, sessionId)
            check(svc.transact(TXN_REPROJECT, data, reply, 0)) {
                "reproject transaction not handled"
            }
            reply.readException()
            reply.readInt().also { state ->
                if (state != STATE_ACTIVE && activeSessionId == sessionId) {
                    activeSessionId = 0L
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reproject transact failed: ${t.message}")
            invalidateService(svc)
            STATE_SERVICE_DOWN
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /** Tear the mirror down (view gone / cast stopped). Best-effort. */
    @Synchronized
    fun detach(sessionId: Long) {
        // The synchronous attach normally makes activeSessionId authoritative. Still send a
        // cleanup for the newest session when local state is uncertain (for example, a reply
        // failed after the daemon adopted the Surface). The daemon's scoped identity check
        // makes this harmless when it has no matching mirror. Older sessions remain suppressed
        // so a delayed Fragment cannot detach a replacement, including against a legacy daemon
        // that ignores the additive session trailer.
        if (activeSessionId != sessionId && newestSessionId.get() != sessionId) return
        inputEpoch.incrementAndGet()
        touchDispatcher.cancel(sessionId)
        val svc = service()
        if (svc == null) {
            activeSessionId = 0L
            return
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            writeSessionIdentity(data, sessionId)
            check(svc.transact(TXN_DETACH, data, reply, 0)) {
                "detach transaction not handled"
            }
            reply.readException()
        } catch (t: Throwable) {
            Log.w(TAG, "detach transact failed: ${t.message}")
            invalidateService(svc)
        } finally {
            if (activeSessionId == sessionId) activeSessionId = 0L
            data.recycle(); reply.recycle()
        }
    }
}
