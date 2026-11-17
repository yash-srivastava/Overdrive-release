package com.overdrive.app.byd

/** Pure autoservice operation, decoding, and generation-scoped session model. */
object AutoServiceProtocol {
    const val LEGACY_INTERFACE_TOKEN = "android.gui.BYDAutoServer"
    const val TX_TYPED_INT = 5
    const val TX_LEGACY_GET_INT = 6
    const val TX_TYPED_FLOAT = 7
    const val TX_LEGACY_GET_BUFFER = 8
    const val TX_LEGACY_REMOTE_CONTROL = 40
    const val TX_LEGACY_SEND_CAN = 42

    enum class ReplyEncoding {
        RAW_INT,
        RAW_BUFFER,
        EXCEPTION_INT,
        STATUS_AND_VALUE
    }

    sealed class Argument {
        data class IntValue(val value: Int) : Argument()
        data class StringValue(val value: String) : Argument()
    }

    enum class Kind {
        LEGACY_GET_INT,
        LEGACY_GET_BUFFER,
        LEGACY_REMOTE_CONTROL,
        LEGACY_SEND_CAN,
        TYPED_INT,
        TYPED_FLOAT
    }

    data class Operation(
        val kind: Kind,
        val transaction: Int,
        val arguments: List<Argument>,
        val replyEncoding: ReplyEncoding
    ) {
        val supportsDiLink5: Boolean
            get() = kind == Kind.TYPED_INT || kind == Kind.TYPED_FLOAT

        companion object {
            fun legacyGetInt(area: Int, command: Int) = Operation(
                Kind.LEGACY_GET_INT,
                TX_LEGACY_GET_INT,
                listOf(Argument.IntValue(area), Argument.IntValue(command)),
                ReplyEncoding.RAW_INT
            )

            fun legacyGetBuffer(area: Int, command: Int) = Operation(
                Kind.LEGACY_GET_BUFFER,
                TX_LEGACY_GET_BUFFER,
                listOf(Argument.IntValue(area), Argument.IntValue(command)),
                ReplyEncoding.RAW_BUFFER
            )

            fun legacyRemoteControl(opcode: Int) = Operation(
                Kind.LEGACY_REMOTE_CONTROL,
                TX_LEGACY_REMOTE_CONTROL,
                listOf(Argument.IntValue(5), Argument.IntValue(opcode)),
                ReplyEncoding.EXCEPTION_INT
            )

            fun legacySendCan(frame: String) = Operation(
                Kind.LEGACY_SEND_CAN,
                TX_LEGACY_SEND_CAN,
                listOf(Argument.IntValue(0), Argument.StringValue(frame)),
                ReplyEncoding.EXCEPTION_INT
            )

            fun typedInt(device: Int, featureId: Int) = Operation(
                Kind.TYPED_INT,
                TX_TYPED_INT,
                listOf(Argument.IntValue(device), Argument.IntValue(featureId)),
                ReplyEncoding.STATUS_AND_VALUE
            )

            fun typedFloat(device: Int, featureId: Int) = Operation(
                Kind.TYPED_FLOAT,
                TX_TYPED_FLOAT,
                listOf(Argument.IntValue(device), Argument.IntValue(featureId)),
                ReplyEncoding.STATUS_AND_VALUE
            )
        }
    }

    data class ModeSnapshot(val mode: String, val generation: String) {
        val isDiLink5: Boolean get() = mode == "dilink5"
        val supportsLegacy: Boolean
            get() = mode == "default" || mode == "dilink4" || mode == "camera2"
    }

    interface WireWriter {
        fun writeInterfaceToken(value: String)
        fun writeInt(value: Int)
        fun writeString(value: String)
    }

    fun writeBinderEnvelope(
        interfaceToken: String,
        operation: Operation,
        writer: WireWriter
    ) {
        writer.writeInterfaceToken(interfaceToken)
        for (argument in operation.arguments) {
            when (argument) {
                is Argument.IntValue -> writer.writeInt(argument.value)
                is Argument.StringValue -> writer.writeString(argument.value)
            }
        }
    }

    fun shellCommand(serviceName: String, operation: Operation): List<String> {
        val command = mutableListOf("service", "call", serviceName, operation.transaction.toString())
        for (argument in operation.arguments) {
            when (argument) {
                is Argument.IntValue -> {
                    command += "i32"
                    command += argument.value.toString()
                }
                is Argument.StringValue -> {
                    command += "s16"
                    command += argument.value
                }
            }
        }
        return command
    }

    enum class Failure(val bridgeCode: Int) {
        NO_BINDER(AutoServiceBridge.ERROR_NO_BINDER),
        TRANSACT_FALSE(AutoServiceBridge.ERROR_TRANSACT_FALSE),
        TRANSACT_EXCEPTION(AutoServiceBridge.ERROR_TRANSACT_EXC),
        SHELL_CALL(AutoServiceBridge.ERROR_SHELL_CALL),
        MALFORMED_REPLY(AutoServiceBridge.ERROR_TRANSACT_EXC),
        UNSUPPORTED_MODE(AutoServiceBridge.ERROR_UNSUPPORTED_MODE)
    }

    data class RawReply(
        val words: List<Int>? = null,
        val bytes: ByteArray? = null,
        val failure: Failure? = null
    ) {
        companion object {
            fun words(vararg values: Int) = RawReply(words = values.toList())
            fun bytes(value: ByteArray) = RawReply(bytes = value.clone())
            fun failed(failure: Failure) = RawReply(failure = failure)
        }
    }

    data class TypedRead(
        val status: Int,
        val valueBits: Int,
        val sentinel: Boolean,
        val integerValue: Int?,
        val floatValue: Float?
    ) {
        val readable: Boolean get() = status == 0 && !sentinel
    }

    interface Session {
        val configuredTransport: String
        val observedConnected: Boolean
        val availableForDispatch: Boolean
        fun dispatch(operation: Operation, generationCurrent: () -> Boolean): RawReply
        fun close()
    }

    fun interface Resolver {
        fun resolve(
            snapshot: ModeSnapshot,
            onDeath: () -> Unit,
            generationCurrent: () -> Boolean
        ): Session?
    }

    data class Status(
        val mode: String?,
        val generation: String?,
        val configuredTransport: String,
        val connected: Boolean,
        val dispatchAvailable: Boolean,
        val readable: Boolean?
    )

    data class Dispatch(
        val accepted: Boolean,
        val raw: RawReply,
        val status: Status
    )

    class Controller(
        private val resolver: Resolver,
        private val configuredTransport: (ModeSnapshot) -> String = {
            if (it.isDiLink5) "binder" else "binder-or-shell"
        }
    ) {
        private data class Cached(
            val snapshot: ModeSnapshot,
            val token: Any,
            val session: Session,
            var readable: Boolean? = null
        )

        private var cached: Cached? = null

        @Synchronized
        fun initialize(snapshot: ModeSnapshot?): Status = statusFor(snapshot, resolve = true) {
            snapshot
        }

        @Synchronized
        fun initialize(
            snapshot: ModeSnapshot?,
            currentSnapshot: () -> ModeSnapshot?
        ): Status = guardedStatus(snapshot, currentSnapshot)

        @Synchronized
        fun status(snapshot: ModeSnapshot?): Status = statusFor(snapshot, resolve = true) {
            snapshot
        }

        @Synchronized
        fun status(
            snapshot: ModeSnapshot?,
            currentSnapshot: () -> ModeSnapshot?
        ): Status = guardedStatus(snapshot, currentSnapshot)

        @Synchronized
        fun dispatch(snapshot: ModeSnapshot?, operation: Operation): Dispatch =
            dispatch(snapshot, operation) { snapshot }

        @Synchronized
        fun dispatch(
            snapshot: ModeSnapshot?,
            operation: Operation,
            currentSnapshot: () -> ModeSnapshot?
        ): Dispatch {
            if (snapshot == null || currentSnapshot() != snapshot || !supports(snapshot, operation)) {
                invalidateLocked()
                val status = statusFor(snapshot, resolve = false) { currentSnapshot() }
                return Dispatch(
                    false,
                    RawReply.failed(Failure.UNSUPPORTED_MODE),
                    status
                )
            }
            val generationCurrent = { currentSnapshot() == snapshot }
            val current = sessionFor(snapshot, currentSnapshot)
            if (current == null) {
                val stale = !generationCurrent()
                return Dispatch(
                    !stale,
                    RawReply.failed(if (stale) Failure.UNSUPPORTED_MODE else Failure.NO_BINDER),
                    statusFor(snapshot, resolve = false) { currentSnapshot() }
                )
            }
            if (!generationCurrent()) {
                invalidateLocked()
                return Dispatch(
                    false,
                    RawReply.failed(Failure.UNSUPPORTED_MODE),
                    statusFor(snapshot, resolve = false) { currentSnapshot() }
                )
            }
            val raw = current.session.dispatch(operation, generationCurrent)
            if (raw.failure == Failure.UNSUPPORTED_MODE || !generationCurrent()) {
                invalidateLocked()
                return Dispatch(
                    false,
                    RawReply.failed(Failure.UNSUPPORTED_MODE),
                    statusFor(snapshot, resolve = false) { currentSnapshot() }
                )
            }
            if (operation.supportsDiLink5) {
                current.readable = decodeTyped(raw, operation.kind == Kind.TYPED_FLOAT)?.readable == true
            }
            return Dispatch(true, raw, current.toStatus())
        }

        @Synchronized
        fun invalidate() = invalidateLocked()

        @Synchronized
        private fun invalidateToken(token: Any) {
            if (cached?.token === token) invalidateLocked()
        }

        private fun guardedStatus(
            snapshot: ModeSnapshot?,
            currentSnapshot: () -> ModeSnapshot?
        ): Status {
            if (snapshot == null || currentSnapshot() != snapshot) {
                invalidateLocked()
                return Status(
                    snapshot?.mode,
                    snapshot?.generation,
                    "unavailable",
                    false,
                    false,
                    null
                )
            }
            return statusFor(
                snapshot,
                resolve = true,
                currentSnapshot = currentSnapshot
            )
        }

        private fun supports(snapshot: ModeSnapshot, operation: Operation): Boolean =
            if (snapshot.isDiLink5) operation.supportsDiLink5
            else snapshot.supportsLegacy && !operation.supportsDiLink5

        private fun statusFor(
            snapshot: ModeSnapshot?,
            resolve: Boolean,
            currentSnapshot: () -> ModeSnapshot?
        ): Status {
            if (snapshot == null) {
                invalidateLocked()
                return Status(null, null, "unavailable", false, false, null)
            }
            if (!snapshot.isDiLink5 && !snapshot.supportsLegacy) {
                invalidateLocked()
                return Status(snapshot.mode, snapshot.generation, "unsupported", false, false, null)
            }
            val value = if (resolve) {
                sessionFor(snapshot, currentSnapshot)
            } else {
                matchingCache(snapshot)
            }
            return value?.toStatus() ?: Status(
                snapshot.mode,
                snapshot.generation,
                configuredTransport(snapshot),
                false,
                false,
                null
            )
        }

        private fun sessionFor(
            snapshot: ModeSnapshot,
            currentSnapshot: () -> ModeSnapshot?
        ): Cached? {
            if (currentSnapshot() != snapshot) {
                invalidateLocked()
                return null
            }
            matchingCache(snapshot)?.let {
                if (currentSnapshot() != snapshot) {
                    invalidateLocked()
                    return null
                }
                if (it.session.availableForDispatch) return it
                invalidateLocked()
            }
            val token = Any()
            val generationCurrent = { currentSnapshot() == snapshot }
            val session = try {
                resolver.resolve(snapshot, { invalidateToken(token) }, generationCurrent)
            } catch (_: Throwable) {
                null
            } ?: return null
            if (!generationCurrent() || !session.availableForDispatch) {
                session.close()
                return null
            }
            val value = Cached(snapshot, token, session)
            cached = value
            return value
        }

        private fun matchingCache(snapshot: ModeSnapshot): Cached? {
            val value = cached ?: return null
            if (value.snapshot != snapshot) {
                invalidateLocked()
                return null
            }
            return value
        }

        private fun Cached.toStatus() = Status(
            snapshot.mode,
            snapshot.generation,
            session.configuredTransport,
            session.observedConnected,
            session.availableForDispatch,
            readable
        )

        private fun invalidateLocked() {
            val old = cached
            cached = null
            old?.session?.close()
        }
    }

    fun decodeTyped(raw: RawReply, asFloat: Boolean): TypedRead? {
        if (raw.failure != null) return null
        val words = raw.words ?: return null
        if (words.size < 2) return null
        val status = words[0]
        val bits = words[1]
        if (asFloat) {
            val value = Float.fromBits(bits)
            val sentinel = value == -1.0f || !value.isFinite()
            return TypedRead(status, bits, sentinel, null, value.takeUnless { sentinel })
        }
        val sentinel = isIntegerSentinel(bits)
        return TypedRead(status, bits, sentinel, bits.takeUnless { sentinel }, null)
    }

    fun isIntegerSentinel(value: Int): Boolean =
        value == 65_535 || value == 1_048_575 || value == -10_013 || value == -10_011

    fun plausibleInteger(read: TypedRead?, minimum: Int, maximum: Int): Boolean? {
        val value = read?.integerValue ?: return null
        if (!read.readable) return null
        return value in minimum..maximum
    }

    fun plausibleFloat(read: TypedRead?, minimum: Float, maximum: Float): Boolean? {
        val value = read?.floatValue ?: return null
        if (!read.readable) return null
        return value in minimum..maximum
    }
}
