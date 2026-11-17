package com.overdrive.app.launcher.dilink5.enable

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded, explicit client for the OEM account file service. */
class DiLink5AccountServiceClient internal constructor(
    private val binding: BindingGateway,
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
) {
    constructor(
        context: Context,
        bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
    ) : this(AndroidBindingGateway(context), bindTimeoutMs)

    enum class ReadState { VALUE, EMPTY, UNAVAILABLE }

    data class ReadResult(val state: ReadState, val value: String? = null)

    data class Result(
        val bound: Boolean,
        val connected: Boolean,
        val binderDied: Boolean,
        val prior: ReadResult,
        val writeResult: Int?,
        val exists: Boolean?,
        val after: ReadResult,
        val detail: String,
        val conditionalMatched: Boolean? = null,
        /** True once the transaction-1 call has crossed the client dispatch boundary. */
        val writeDispatched: Boolean = false,
        /** True only when transaction 1 was dispatched but no definite integer reply was decoded. */
        val replyLostAfterDispatch: Boolean = false,
    )

    data class ParcelRequest(val transaction: Int, val strings: List<String>)

    internal interface ServiceChannel {
        val isAlive: Boolean
        val supportsProtocol: Boolean get() = true
        fun linkToDeath(onDeath: () -> Unit)
        fun unlinkToDeath()
        fun write(request: ParcelRequest): Int?
        fun read(request: ParcelRequest): ReadResult
        fun exists(request: ParcelRequest): Boolean?
    }

    internal interface BindingCallback {
        fun onConnected(channel: ServiceChannel)
        fun onDisconnected()
        fun onBindingDied()
        fun onNullBinding()
    }

    internal interface BindingGateway {
        fun bind(callback: BindingCallback): Boolean
        fun unbind(callback: BindingCallback)
    }

    companion object {
        const val ACTION = "byd.intent.action.SAVE_READ_FILE"
        const val PACKAGE = "com.byd.accountservice"
        const val SERVICE = "com.byd.accountservice.service.SaveAndReadFileService"
        const val DESCRIPTOR = "com.byd.accountservice.ISaveAndReadFileInterface"
        const val FLAG_DIRECTORY = "/collect2/activate/"
        const val FLAG_FILENAME = "../autotest"
        const val FLAG_PATH = "/collect2/autotest"
        const val READABLE_FLAG_PATH = "/collect2/activate/../autotest"
        const val ENABLE_CONTENT = "1"
        private const val DEFAULT_BIND_TIMEOUT_MS = 4_000L

        fun writeRequest(content: String) = ParcelRequest(
            1,
            listOf(FLAG_DIRECTORY, FLAG_FILENAME, content),
        )

        fun readRequest(path: String = READABLE_FLAG_PATH) = ParcelRequest(2, listOf(path))
        fun existsRequest(path: String = FLAG_PATH) = ParcelRequest(3, listOf(path))

        internal fun hasReplyWord(availableBytes: Int): Boolean = availableBytes >= Int.SIZE_BYTES
    }

    fun inspect(isCurrent: () -> Boolean = { true }): Result = withService { service, died ->
        if (!isCurrent()) return@withService unavailable(true, died.get(), "inspection authority revoked")
        val prior = service.read(readRequest())
        if (!isCurrent()) return@withService Result(
            true, true, died.get(), prior, null, null, prior, "inspection authority revoked",
        )
        val exists = service.exists(existsRequest())
        Result(true, true, died.get(), prior, null, exists, prior, "inspection complete")
    }

    fun write(content: String, isCurrent: () -> Boolean = { true }): Result {
        val dispatched = AtomicBoolean(false)
        val replyReceived = AtomicBoolean(false)
        val replyValue = AtomicReference<Int?>()
        val result = withService { service, died ->
            val prior = service.read(readRequest())
            if (!isCurrent()) {
                return@withService Result(
                    true,
                    true,
                    died.get(),
                    prior,
                    null,
                    null,
                    prior,
                    "mode authority revoked before write dispatch",
                )
            }
            dispatched.set(true)
            val write = service.write(writeRequest(content))
            replyValue.set(write)
            replyReceived.set(write != null)
            val exists = service.exists(existsRequest())
            val after = service.read(readRequest())
            Result(
                true,
                true,
                died.get(),
                prior,
                write,
                exists,
                after,
                if (write == null) "write reply unavailable after dispatch" else "write complete",
                writeDispatched = true,
                replyLostAfterDispatch = write == null,
            )
        }
        if (!dispatched.get()) return result
        return result.copy(
            writeResult = if (replyReceived.get()) replyValue.get() else result.writeResult,
            writeDispatched = true,
            replyLostAfterDispatch = !replyReceived.get(),
            detail = if (!replyReceived.get()) {
                "write reply unavailable after dispatch"
            } else {
                result.detail
            },
        )
    }

    /** Reads and conditionally restores on one bound, serialized worker operation. */
    fun restore(
        expectedCurrent: String,
        restoreValue: String,
        isCurrent: () -> Boolean = { true },
    ): Result = withService { service, died ->
        val current = service.read(readRequest())
        val currentMatches = if (expectedCurrent.isEmpty()) {
            current.state == ReadState.EMPTY && current.value.orEmpty().isEmpty()
        } else {
            current.state == ReadState.VALUE && current.value == expectedCurrent
        }
        if (!currentMatches) {
            Result(
                true,
                true,
                died.get(),
                current,
                null,
                null,
                current,
                "conditional restore rejected because current content did not match",
                conditionalMatched = false,
            )
        } else if (!isCurrent()) {
            Result(
                true,
                true,
                died.get(),
                current,
                null,
                null,
                current,
                "mode authority revoked before restore dispatch",
                conditionalMatched = true,
            )
        } else {
            val write = service.write(writeRequest(restoreValue))
            val exists = service.exists(existsRequest())
            val after = service.read(readRequest())
            Result(
                true,
                true,
                died.get(),
                current,
                write,
                exists,
                after,
                if (write == null) "restore reply unavailable after dispatch" else "conditional restore complete",
                conditionalMatched = true,
                writeDispatched = true,
                replyLostAfterDispatch = write == null,
            )
        }
    }

    private fun withService(block: (ServiceChannel, AtomicBoolean) -> Result): Result {
        val latch = CountDownLatch(1)
        val serviceRef = AtomicReference<ServiceChannel?>()
        val disconnected = AtomicBoolean(false)
        val died = AtomicBoolean(false)
        var bound = false
        val callback = object : BindingCallback {
            override fun onConnected(channel: ServiceChannel) {
                serviceRef.set(channel)
                latch.countDown()
            }

            override fun onDisconnected() {
                disconnected.set(true)
                serviceRef.set(null)
                latch.countDown()
            }

            override fun onBindingDied() {
                died.set(true)
                disconnected.set(true)
                serviceRef.set(null)
                latch.countDown()
            }

            override fun onNullBinding() {
                serviceRef.set(null)
                latch.countDown()
            }
        }
        return try {
            bound = binding.bind(callback)
            if (!bound) return unavailable(false, false, "bind rejected")
            if (!latch.await(bindTimeoutMs, TimeUnit.MILLISECONDS)) {
                return unavailable(true, false, "bind timed out")
            }
            val service = serviceRef.get() ?: return unavailable(true, died.get(), "service disconnected")
            try {
                service.linkToDeath {
                    died.set(true)
                    disconnected.set(true)
                    serviceRef.compareAndSet(service, null)
                }
                if (!service.isAlive || disconnected.get()) {
                    unavailable(true, died.get(), "binder unavailable")
                } else if (!service.supportsProtocol) {
                    unavailable(true, died.get(), "unsupported account service interface")
                } else {
                    block(service, died).copy(
                        connected = !disconnected.get(),
                        binderDied = died.get(),
                    )
                }
            } finally {
                try {
                    service.unlinkToDeath()
                } catch (_: Throwable) {
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            unavailable(bound, died.get(), "interrupted")
        } catch (_: SecurityException) {
            unavailable(bound, died.get(), "binding denied")
        } catch (error: Throwable) {
            unavailable(bound, died.get(), error.javaClass.simpleName)
        } finally {
            if (bound) {
                try {
                    binding.unbind(callback)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun unavailable(bound: Boolean, binderDied: Boolean, detail: String) = Result(
        bound = bound,
        connected = false,
        binderDied = binderDied,
        prior = ReadResult(ReadState.UNAVAILABLE),
        writeResult = null,
        exists = null,
        after = ReadResult(ReadState.UNAVAILABLE),
        detail = detail,
    )

    private class AndroidBindingGateway(private val context: Context) : BindingGateway {
        private val connections = IdentityHashMap<BindingCallback, ServiceConnection>()

        override fun bind(callback: BindingCallback): Boolean {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    callback.onConnected(AndroidServiceChannel(service))
                }

                override fun onServiceDisconnected(name: ComponentName) = callback.onDisconnected()
                override fun onBindingDied(name: ComponentName) = callback.onBindingDied()
                override fun onNullBinding(name: ComponentName) = callback.onNullBinding()
            }
            synchronized(connections) { connections[callback] = connection }
            val accepted = try {
                context.bindService(
                    Intent(ACTION).setComponent(ComponentName(PACKAGE, SERVICE)),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (error: Throwable) {
                synchronized(connections) { connections.remove(callback) }
                throw error
            }
            if (!accepted) synchronized(connections) { connections.remove(callback) }
            return accepted
        }

        override fun unbind(callback: BindingCallback) {
            val connection = synchronized(connections) { connections.remove(callback) } ?: return
            context.unbindService(connection)
        }
    }

    private class AndroidServiceChannel(private val binder: IBinder) : ServiceChannel {
        private var deathRecipient: IBinder.DeathRecipient? = null

        override val isAlive: Boolean get() = binder.isBinderAlive
        override val supportsProtocol: Boolean
            get() = try {
                binder.interfaceDescriptor == DESCRIPTOR
            } catch (_: Throwable) {
                false
            }

        override fun linkToDeath(onDeath: () -> Unit) {
            val recipient = IBinder.DeathRecipient(onDeath)
            binder.linkToDeath(recipient, 0)
            deathRecipient = recipient
        }

        override fun unlinkToDeath() {
            val recipient = deathRecipient ?: return
            deathRecipient = null
            binder.unlinkToDeath(recipient, 0)
        }

        override fun write(request: ParcelRequest): Int? = transact(request) { reply ->
            if (!hasReplyWord(reply.dataAvail())) throw IllegalStateException("missing write result")
            reply.readInt()
        }

        override fun read(request: ParcelRequest): ReadResult = transact(request) { reply ->
            if (!hasReplyWord(reply.dataAvail())) throw IllegalStateException("missing read result")
            val value = reply.readString() ?: return@transact ReadResult(ReadState.UNAVAILABLE)
            if (value.isEmpty()) ReadResult(ReadState.EMPTY, "") else ReadResult(ReadState.VALUE, value)
        } ?: ReadResult(ReadState.UNAVAILABLE)

        override fun exists(request: ParcelRequest): Boolean? = transact(request) { reply ->
            if (!hasReplyWord(reply.dataAvail())) throw IllegalStateException("missing existence result")
            reply.readInt() != 0
        }

        private fun <T> transact(request: ParcelRequest, decode: (Parcel) -> T): T? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                request.strings.forEach(data::writeString)
                if (!binder.transact(request.transaction, data, reply, 0)) return null
                reply.readException()
                decode(reply)
            } catch (_: Throwable) {
                null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}
