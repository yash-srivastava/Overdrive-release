package com.overdrive.app.byd.dilink5

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import com.overdrive.app.byd.VehicleActuatorBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** App-process client for the daemon's closed native vehicle action service. */
object NativeVehicleWriteClient {
    data class WriteResult(
        @JvmField val kind: NativeVehicleWriteBoundary.Kind,
        @JvmField val hasStatus: Boolean = false,
        @JvmField val status: Int = 0,
        @JvmField val hasOptionalWord: Boolean = false,
        @JvmField val optionalWord: Int = 0,
        @JvmField val observed: Boolean = false,
    ) {
        val accepted: Boolean
            get() = kind == NativeVehicleWriteBoundary.Kind.ACCEPTED

        val uncertain: Boolean
            get() = kind == NativeVehicleWriteBoundary.Kind.TRANSACT_FALSE ||
                kind == NativeVehicleWriteBoundary.Kind.MISSING_STATUS ||
                kind == NativeVehicleWriteBoundary.Kind.DEAD_SERVICE ||
                kind == NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR
    }

    interface ReplyReader {
        fun remaining(): Int
        fun readInt(): Int
    }

    interface CaptureTransport {
        fun transact(code: Int, descriptor: String): Long?
    }

    interface TailgateStopRegistration : AutoCloseable {
        override fun close()
    }

    interface RequestEnvelopeWriter {
        fun writeInterfaceToken(value: String)
        fun writeInt(value: Int)
        fun writeString(value: String?)
        fun writeLong(value: Long)
        fun writeOwner(value: Any)
    }

    private val requestSequence = AtomicLong(1L)
    @Volatile private var delivered: IBinder? = null

    private val handoffCallback: IBinder by lazy {
        object : Binder() {
            init {
                attachInterface(null, NativeVehicleWriteService.HANDOFF_DESCRIPTOR)
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != NativeVehicleWriteService.TX_OFFER) {
                    return super.onTransact(code, data, reply, flags)
                }
                return try {
                    data.enforceInterface(NativeVehicleWriteService.HANDOFF_DESCRIPTOR)
                    val binder = data.readStrongBinder()
                    val stopGeneration = if (data.dataAvail() == 8) data.readLong() else -1L
                    if (data.dataAvail() != 0 || stopGeneration < 0L) false else {
                        offerBinder(binder)
                        binder != null && binder.isBinderAlive
                    }
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    @JvmStatic
    fun offerBinder(binder: IBinder?) {
        if (binder != null && binder.isBinderAlive) delivered = binder
    }

    /** Requests the daemon Binder over a signature-permission-gated one-shot callback. */
    @JvmStatic
    fun requestBinder(context: Context?): Boolean {
        if (context == null) return false
        if (service() != null) return true
        return try {
            val extras = android.os.Bundle().apply {
                putBinder(NativeVehicleWriteService.EXTRA_CALLBACK, handoffCallback)
            }
            context.sendBroadcast(
                Intent(NativeVehicleWriteService.ACTION_REQUEST)
                    .setPackage(TrustedAppUidResolver.APP_PACKAGE)
                    .putExtras(extras),
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Bounded late-client lookup; it never creates a service in the app process. */
    @JvmStatic
    fun acquireBinder(context: Context?, timeoutMs: Long): IBinder? {
        service()?.let { return it }
        if (!requestBinder(context) || timeoutMs <= 0L) return service()
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!Thread.currentThread().isInterrupted &&
            android.os.SystemClock.elapsedRealtime() < deadline
        ) {
            service()?.let { return it }
            try {
                Thread.sleep(20L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return service()
    }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun captureTailgateStopGeneration(context: Context?, timeoutMs: Long): Long? {
        // This request may use only authority available before bootstrap. A late offer could
        // already include a STOP that arrived while the broadcast was waiting to be handled.
        return captureTailgateStopGeneration(service()?.let(::binderCaptureTransport)) {
            requestBinder(context)
        }
    }

    internal fun captureTailgateStopGeneration(
        available: CaptureTransport?,
        bootstrap: () -> Unit,
    ): Long? {
        if (available == null) {
            bootstrap()
            return null
        }
        return captureTailgateStopGeneration(available)
    }

    /** Cancels app-owned cloud work when the daemon revokes its captured OPEN authority. */
    @JvmStatic
    fun registerTailgateStopCancellation(
        expectedGeneration: Long,
        onStop: Runnable,
    ): TailgateStopRegistration? {
        if (expectedGeneration < 0L) return null
        val binder = service() ?: return null
        val lock = Any()
        var active = true
        var stopped = false
        fun cancelOwnedWork() {
            synchronized(lock) {
                if (!active || stopped) return
                stopped = true
                onStop.run()
            }
        }
        val callback = object : Binder() {
            init {
                attachInterface(null, NativeVehicleWriteService.STOP_CALLBACK_DESCRIPTOR)
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != NativeVehicleWriteService.TX_NOTIFY_TAILGATE_STOP) {
                    return super.onTransact(code, data, reply, flags)
                }
                return try {
                    data.enforceInterface(NativeVehicleWriteService.STOP_CALLBACK_DESCRIPTOR)
                    if (data.dataAvail() != 0) false else {
                        cancelOwnedWork()
                        true
                    }
                } catch (_: Throwable) {
                    false
                }
            }
        }
        val death = IBinder.DeathRecipient { cancelOwnedWork() }
        val registration = object : TailgateStopRegistration {
            override fun close() {
                synchronized(lock) {
                    if (!active) return
                    active = false
                }
                try {
                    binder.unlinkToDeath(death, 0)
                } catch (_: Throwable) {
                }
                tailgateStopRegistrationTransaction(
                    binder, NativeVehicleWriteService.TX_UNREGISTER_TAILGATE_STOP,
                    expectedGeneration, callback,
                )
            }
        }
        return try {
            binder.linkToDeath(death, 0)
            val registered = tailgateStopRegistrationTransaction(
                binder, NativeVehicleWriteService.TX_REGISTER_TAILGATE_STOP,
                expectedGeneration, callback,
            )
            if (!registered || synchronized(lock) { stopped }) {
                registration.close()
                null
            } else {
                registration
            }
        } catch (_: Throwable) {
            registration.close()
            null
        }
    }

    private fun tailgateStopRegistrationTransaction(
        binder: IBinder,
        code: Int,
        generation: Long,
        callback: IBinder,
    ): Boolean {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            request.writeInterfaceToken(NativeVehicleWriteBoundary.DESCRIPTOR)
            if (code == NativeVehicleWriteService.TX_REGISTER_TAILGATE_STOP) {
                request.writeLong(generation)
            }
            request.writeStrongBinder(callback)
            if (!binder.transact(code, request, response, 0)) return false
            response.readException()
            response.dataAvail() == 4 && response.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            request.recycle()
            response.recycle()
        }
    }

    private fun binderCaptureTransport(binder: IBinder) = object : CaptureTransport {
        override fun transact(code: Int, descriptor: String): Long? {
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            return try {
                request.writeInterfaceToken(descriptor)
                if (!binder.transact(code, request, response, 0)) return null
                response.readException()
                if (response.dataAvail() != 8) return null
                response.readLong()
            } catch (_: Throwable) {
                null
            } finally {
                request.recycle()
                response.recycle()
            }
        }
    }

    internal fun captureTailgateStopGeneration(transport: CaptureTransport): Long? =
        transport.transact(
            NativeVehicleWriteService.TX_CAPTURE_TAILGATE_STOP_GENERATION,
            NativeVehicleWriteBoundary.DESCRIPTOR,
        )?.takeIf { it >= 0L }

    @JvmStatic
    fun execute(
        action: NativeVehicleAction?,
        binderHint: IBinder?,
        modeGeneration: String?,
        deadlineElapsedMs: Long,
        tailgateStopGeneration: Long,
    ): WriteResult {
        if (action == null || NativeVehicleAction.fromTuple(
                action.device, action.featureId, action.value) !== action) {
            return WriteResult(NativeVehicleWriteBoundary.Kind.UNKNOWN_ACTION)
        }
        if (modeGeneration.isNullOrEmpty() || deadlineElapsedMs <= 0L ||
            android.os.SystemClock.elapsedRealtime() >= deadlineElapsedMs
        ) {
            return WriteResult(NativeVehicleWriteBoundary.Kind.STALE_REQUEST)
        }
        val binder = binderHint?.takeIf { it.isBinderAlive } ?: service()
            ?: return WriteResult(NativeVehicleWriteBoundary.Kind.DEAD_SERVICE)
        val requestId = nextRequestId()
        val owner = Binder()
        val cancellation = VehicleActuatorBridge.currentDiLink5Cancellation()
        val cancellationActive = AtomicBoolean(true)
        cancellation?.onRemoteCancellation {
            if (cancellationActive.get()) sendCancellation(binder, requestId, owner)
        }
        if (cancellation?.isCancelled == true || Thread.currentThread().isInterrupted) {
            cancellationActive.set(false)
            return WriteResult(NativeVehicleWriteBoundary.Kind.CANCELLED)
        }

        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            writeRequestEnvelope(
                object : RequestEnvelopeWriter {
                    override fun writeInterfaceToken(value: String) = request.writeInterfaceToken(value)
                    override fun writeInt(value: Int) = request.writeInt(value)
                    override fun writeString(value: String?) = request.writeString(value)
                    override fun writeLong(value: Long) = request.writeLong(value)
                    override fun writeOwner(value: Any) = request.writeStrongBinder(value as IBinder)
                },
                action,
                modeGeneration,
                deadlineElapsedMs,
                requestId,
                owner,
                tailgateStopGeneration,
            )
            if (!binder.transact(NativeVehicleWriteService.TX_WRITE, request, response, 0)) {
                WriteResult(NativeVehicleWriteBoundary.Kind.TRANSACT_FALSE)
            } else {
                response.readException()
                decodeReply(object : ReplyReader {
                    override fun remaining(): Int = response.dataAvail()
                    override fun readInt(): Int = response.readInt()
                })
            }
        } catch (_: DeadObjectException) {
            if (delivered === binder) delivered = null
            WriteResult(NativeVehicleWriteBoundary.Kind.DEAD_SERVICE)
        } catch (_: Throwable) {
            if (!binder.isBinderAlive && delivered === binder) delivered = null
            WriteResult(
                if (binder.isBinderAlive) NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR
                else NativeVehicleWriteBoundary.Kind.DEAD_SERVICE,
            )
        } finally {
            cancellationActive.set(false)
            request.recycle()
            response.recycle()
        }
    }

    internal fun writeRequestEnvelope(
        writer: RequestEnvelopeWriter,
        action: NativeVehicleAction,
        modeGeneration: String?,
        deadlineElapsedMs: Long,
        requestId: Long,
        owner: Any,
        tailgateStopGeneration: Long,
    ) {
        writer.writeInterfaceToken(NativeVehicleWriteBoundary.DESCRIPTOR)
        writer.writeInt(action.device)
        writer.writeInt(action.featureId)
        writer.writeInt(action.value)
        writer.writeString(modeGeneration)
        writer.writeLong(deadlineElapsedMs)
        writer.writeLong(requestId)
        writer.writeOwner(owner)
        writer.writeLong(tailgateStopGeneration)
    }

    /** Strict decoder for the complete outer Binder reply envelope. */
    @JvmStatic
    fun decodeReply(reader: ReplyReader?): WriteResult {
        if (reader == null || reader.remaining() < 4) {
            return WriteResult(NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR)
        }
        return try {
            val kind = NativeVehicleWriteBoundary.Kind.values().getOrNull(reader.readInt())
                ?: return WriteResult(NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR)
            val hasStatus = readFlag(reader)
                ?: return WriteResult(NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR)
            if (hasStatus && reader.remaining() < 4) {
                return WriteResult(NativeVehicleWriteBoundary.Kind.MISSING_STATUS)
            }
            val status = if (hasStatus) reader.readInt() else 0
            val hasOptional = readFlag(reader)
                ?: return WriteResult(NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR)
            if (hasOptional && (!hasStatus || reader.remaining() < 4)) {
                return WriteResult(NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR)
            }
            val optional = if (hasOptional) reader.readInt() else 0
            val observed = readFlag(reader)
                ?: return WriteResult(NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR)
            if (reader.remaining() != 0 || observed ||
                ((kind == NativeVehicleWriteBoundary.Kind.ACCEPTED ||
                    kind == NativeVehicleWriteBoundary.Kind.REJECTED) != hasStatus) ||
                (kind == NativeVehicleWriteBoundary.Kind.ACCEPTED && status < 0) ||
                (kind == NativeVehicleWriteBoundary.Kind.REJECTED && status >= 0)
            ) {
                WriteResult(NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR)
            } else {
                WriteResult(kind, hasStatus, status, hasOptional, optional, false)
            }
        } catch (_: Throwable) {
            WriteResult(NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR)
        }
    }

    private fun readFlag(reader: ReplyReader): Boolean? {
        if (reader.remaining() < 4) return null
        return when (reader.readInt()) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    private fun sendCancellation(binder: IBinder, requestId: Long, owner: IBinder) {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(NativeVehicleWriteBoundary.DESCRIPTOR)
            request.writeLong(requestId)
            request.writeStrongBinder(owner)
            if (binder.transact(NativeVehicleWriteService.TX_CANCEL, request, response, 0)) {
                response.readException()
            }
        } catch (_: Throwable) {
        } finally {
            request.recycle()
            response.recycle()
        }
    }

    private fun nextRequestId(): Long {
        while (true) {
            val value = requestSequence.getAndIncrement()
            if (value > 0L) return value
            requestSequence.compareAndSet(value + 1L, 1L)
        }
    }

    private fun service(): IBinder? {
        delivered?.takeIf { it.isBinderAlive }?.let { return it }
        return try {
            val manager = Class.forName("android.os.ServiceManager")
            (manager.getMethod("getService", String::class.java)
                .invoke(null, NativeVehicleWriteService.SERVICE_NAME) as? IBinder)
                ?.takeIf { it.isBinderAlive }
                ?.also { delivered = it }
        } catch (_: Throwable) {
            null
        }
    }
}
