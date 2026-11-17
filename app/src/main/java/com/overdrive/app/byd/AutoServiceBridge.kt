package com.overdrive.app.byd

import android.os.IBinder
import android.os.Parcel
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.shell.HiddenApiBypass
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

/** Generation-fenced bridge to the platform autoservice Binder. */
object AutoServiceBridge {
    private const val TAG = "AutoServiceBridge"
    private const val SERVICE_NAME = "autoservice"

    const val OP_UNLOCK = 5
    const val OP_TRUNK_OPEN = 6
    const val OP_LOCK = 7
    const val OP_POWER_OFF = 9
    const val OP_CLOSE_WINDOWS = 17
    const val OP_FLASH_LIGHTS = 22

    const val INVALID_VALUE = -10011
    const val ERROR_NO_BINDER = -1
    const val ERROR_TRANSACT_FALSE = -2
    const val ERROR_TRANSACT_EXC = -3
    const val ERROR_SHELL_CALL = -10010
    const val ERROR_UNSUPPORTED_MODE = -10012

    private val parcelInt32Pattern: Pattern = Pattern.compile("0x([0-9a-fA-F]{8})")
    private val parcelByteHexPattern: Pattern = Pattern.compile("0x([0-9a-fA-F]{2})")

    private val controller = AutoServiceProtocol.Controller(AndroidResolver())

    data class DiLink5Read(
        @JvmField val status: Int,
        @JvmField val valueBits: Int
    )

    data class DiagnosticStatus(
        @JvmField val mode: String?,
        @JvmField val generation: String?,
        @JvmField val configuredTransport: String,
        @JvmField val connected: Boolean,
        @JvmField val dispatchAvailable: Boolean,
        @JvmField val readable: Boolean?
    )

    /** Resolves only the transport appropriate for the committed generation. */
    fun init(): Boolean = init(currentSnapshot())

    fun init(expected: AutoServiceProtocol.ModeSnapshot?): Boolean {
        val status = controller.initialize(expected, ::currentSnapshot)
        return if (expected?.isDiLink5 == true) status.connected else status.dispatchAvailable
    }

    /** Preserves legacy shell availability while requiring a live Binder on DiLink 5. */
    fun isConnected(): Boolean = isConnected(currentSnapshot())

    fun isConnected(expected: AutoServiceProtocol.ModeSnapshot?): Boolean {
        val status = controller.status(expected, ::currentSnapshot)
        return if (expected?.isDiLink5 == true) status.connected else status.dispatchAvailable
    }

    fun isUsingShellCall(): Boolean = isUsingShellCall(currentSnapshot())

    fun isUsingShellCall(expected: AutoServiceProtocol.ModeSnapshot?): Boolean =
        controller.status(expected, ::currentSnapshot).configuredTransport == "shell"

    fun diagnosticStatus(): DiagnosticStatus = diagnosticStatus(currentSnapshot())

    fun diagnosticStatus(expected: AutoServiceProtocol.ModeSnapshot?): DiagnosticStatus {
        val status = controller.status(expected, ::currentSnapshot)
        return DiagnosticStatus(
            status.mode,
            status.generation,
            status.configuredTransport,
            status.connected,
            status.dispatchAvailable,
            status.readable
        )
    }

    fun getInt(area: Int, cmd: Int): Int = getInt(currentSnapshot(), area, cmd)

    fun getInt(expected: AutoServiceProtocol.ModeSnapshot?, area: Int, cmd: Int): Int = legacyInt(
        expected,
        AutoServiceProtocol.Operation.legacyGetInt(area, cmd)
    )

    fun getBuffer(area: Int, cmd: Int): ByteArray? = getBuffer(currentSnapshot(), area, cmd)

    fun getBuffer(
        expected: AutoServiceProtocol.ModeSnapshot?,
        area: Int,
        cmd: Int
    ): ByteArray? {
        val result = controller.dispatch(
            expected,
            AutoServiceProtocol.Operation.legacyGetBuffer(area, cmd),
            ::currentSnapshot
        )
        if (!result.accepted || result.raw.failure != null) return null
        return result.raw.bytes?.clone()
    }

    fun readDiLink5(tx: Int, device: Int, featureId: Int): DiLink5Read? =
        readDiLink5(currentSnapshot(), tx, device, featureId)

    fun readDiLink5(
        expected: AutoServiceProtocol.ModeSnapshot?,
        tx: Int,
        device: Int,
        featureId: Int
    ): DiLink5Read? {
        val snapshot = expected ?: return null
        val operation = when (tx) {
            AutoServiceProtocol.TX_TYPED_INT ->
                AutoServiceProtocol.Operation.typedInt(device, featureId)
            AutoServiceProtocol.TX_TYPED_FLOAT ->
                AutoServiceProtocol.Operation.typedFloat(device, featureId)
            else -> return null
        }
        val result = controller.dispatch(snapshot, operation, ::currentSnapshot)
        if (!result.accepted) return null
        val decoded = AutoServiceProtocol.decodeTyped(
            result.raw,
            operation.kind == AutoServiceProtocol.Kind.TYPED_FLOAT
        ) ?: return null
        return DiLink5Read(decoded.status, decoded.valueBits)
    }

    fun unlockCar(): Int = remoteControl(OP_UNLOCK)
    fun lockCar(): Int = remoteControl(OP_LOCK)
    fun openTrunk(): Int = remoteControl(OP_TRUNK_OPEN)
    fun powerOff(): Int = remoteControl(OP_POWER_OFF)
    fun closeWindows(): Int = remoteControl(OP_CLOSE_WINDOWS)
    fun flashLights(): Int = remoteControl(OP_FLASH_LIGHTS)

    fun remoteControl(opcode: Int): Int = remoteControl(currentSnapshot(), opcode)

    fun remoteControl(expected: AutoServiceProtocol.ModeSnapshot?, opcode: Int): Int = legacyInt(
        expected,
        AutoServiceProtocol.Operation.legacyRemoteControl(opcode)
    )

    fun sendCanViaService(hexFrame: String): Int =
        sendCanViaService(currentSnapshot(), hexFrame)

    fun sendCanViaService(
        expected: AutoServiceProtocol.ModeSnapshot?,
        hexFrame: String
    ): Int = legacyInt(expected, AutoServiceProtocol.Operation.legacySendCan(hexFrame))

    private fun legacyInt(
        expected: AutoServiceProtocol.ModeSnapshot?,
        operation: AutoServiceProtocol.Operation
    ): Int {
        val result = controller.dispatch(expected, operation, ::currentSnapshot)
        if (!result.accepted) return ERROR_UNSUPPORTED_MODE
        result.raw.failure?.let { return it.bridgeCode }
        return result.raw.words?.firstOrNull() ?: ERROR_TRANSACT_EXC
    }

    private fun currentSnapshot(): AutoServiceProtocol.ModeSnapshot? {
        val value = DiLink5Platform.currentActiveModeSnapshot() ?: return null
        return AutoServiceProtocol.ModeSnapshot(value.mode, value.generation)
    }

    private class AndroidResolver : AutoServiceProtocol.Resolver {
        override fun resolve(
            snapshot: AutoServiceProtocol.ModeSnapshot,
            onDeath: () -> Unit,
            generationCurrent: () -> Boolean
        ): AutoServiceProtocol.Session? {
            if (!generationCurrent()) return null
            val resolved = resolveBinder()
            if (!generationCurrent()) return null
            if (snapshot.isDiLink5) {
                val binder = resolved ?: return null
                val token = try {
                    binder.interfaceDescriptor
                } catch (_: Throwable) {
                    null
                }
                if (!generationCurrent() || token.isNullOrEmpty()) return null
                return BinderSession(binder, token, onDeath)
            }
            if (!snapshot.supportsLegacy) return null
            if (resolved == null) return if (generationCurrent()) ShellSession() else null
            return try {
                if (!probeLegacyBinder(resolved, generationCurrent)) return null
                BinderSession(resolved, AutoServiceProtocol.LEGACY_INTERFACE_TOKEN, onDeath)
            } catch (_: SecurityException) {
                if (!generationCurrent()) return null
                log("direct Binder access denied; using the legacy shell transport")
                ShellSession()
            } catch (_: Throwable) {
                if (!generationCurrent()) return null
                BinderSession(resolved, AutoServiceProtocol.LEGACY_INTERFACE_TOKEN, onDeath)
            }
        }

        private fun resolveBinder(): IBinder? {
            if (!HiddenApiBypass.isBypassed()) HiddenApiBypass.bypass()
            return try {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val value = serviceManager.getMethod("getService", String::class.java)
                    .invoke(null, SERVICE_NAME) as? IBinder
                value?.takeIf {
                    try {
                        it.pingBinder()
                    } catch (_: Throwable) {
                        false
                    }
                }
            } catch (error: Throwable) {
                log("Binder resolve failed: ${error.javaClass.simpleName}: ${error.message}")
                null
            }
        }

        private fun probeLegacyBinder(
            binder: IBinder,
            generationCurrent: () -> Boolean
        ): Boolean {
            if (!generationCurrent()) return false
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(AutoServiceProtocol.LEGACY_INTERFACE_TOKEN)
                data.writeInt(0)
                data.writeInt(0)
                if (!generationCurrent()) return false
                binder.transact(AutoServiceProtocol.TX_LEGACY_GET_BUFFER, data, reply, 0)
                return true
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private class BinderSession(
        private val binder: IBinder,
        private val interfaceToken: String,
        private val onDeath: () -> Unit
    ) : AutoServiceProtocol.Session {
        private val deathRecipient = object : IBinder.DeathRecipient {
            override fun binderDied() = onDeath()
        }

        init {
            binder.linkToDeath(deathRecipient, 0)
        }

        override val configuredTransport: String = "binder"

        override val observedConnected: Boolean
            get() = try {
                binder.pingBinder()
            } catch (_: Throwable) {
                false
            }

        override val availableForDispatch: Boolean
            get() = observedConnected

        override fun dispatch(
            operation: AutoServiceProtocol.Operation,
            generationCurrent: () -> Boolean
        ): AutoServiceProtocol.RawReply {
            if (!generationCurrent()) {
                return AutoServiceProtocol.RawReply.failed(
                    AutoServiceProtocol.Failure.UNSUPPORTED_MODE
                )
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                AutoServiceProtocol.writeBinderEnvelope(
                    interfaceToken,
                    operation,
                    object : AutoServiceProtocol.WireWriter {
                        override fun writeInterfaceToken(value: String) =
                            data.writeInterfaceToken(value)

                        override fun writeInt(value: Int) = data.writeInt(value)

                        override fun writeString(value: String) = data.writeString(value)
                    }
                )
                if (!generationCurrent()) {
                    AutoServiceProtocol.RawReply.failed(
                        AutoServiceProtocol.Failure.UNSUPPORTED_MODE
                    )
                } else if (!binder.transact(operation.transaction, data, reply, 0)) {
                    AutoServiceProtocol.RawReply.failed(
                        AutoServiceProtocol.Failure.TRANSACT_FALSE
                    )
                } else {
                    decodeReply(operation, reply)
                }
            } catch (error: Throwable) {
                log("transaction ${operation.transaction} failed: "
                        + "${error.javaClass.simpleName}: ${error.message}")
                AutoServiceProtocol.RawReply.failed(
                    AutoServiceProtocol.Failure.TRANSACT_EXCEPTION
                )
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun close() {
            try {
                binder.unlinkToDeath(deathRecipient, 0)
            } catch (_: Throwable) {
            }
        }

        private fun decodeReply(
            operation: AutoServiceProtocol.Operation,
            reply: Parcel
        ): AutoServiceProtocol.RawReply {
            reply.setDataPosition(0)
            return when (operation.replyEncoding) {
                AutoServiceProtocol.ReplyEncoding.RAW_INT -> {
                    if (reply.dataAvail() < 4) malformed()
                    else AutoServiceProtocol.RawReply.words(reply.readInt())
                }
                AutoServiceProtocol.ReplyEncoding.EXCEPTION_INT -> {
                    reply.readException()
                    if (reply.dataAvail() < 4) malformed()
                    else AutoServiceProtocol.RawReply.words(reply.readInt())
                }
                AutoServiceProtocol.ReplyEncoding.STATUS_AND_VALUE -> {
                    if (reply.dataAvail() < 8) malformed()
                    else AutoServiceProtocol.RawReply.words(reply.readInt(), reply.readInt())
                }
                AutoServiceProtocol.ReplyEncoding.RAW_BUFFER -> {
                    if (reply.dataAvail() < 4) return malformed()
                    val length = reply.readInt()
                    if (length !in 1..4096 || reply.dataAvail() < length) return malformed()
                    val value = ByteArray(length)
                    reply.readByteArray(value)
                    AutoServiceProtocol.RawReply.bytes(value)
                }
            }
        }

        private fun malformed() = AutoServiceProtocol.RawReply.failed(
            AutoServiceProtocol.Failure.MALFORMED_REPLY
        )
    }

    private class ShellSession : AutoServiceProtocol.Session {
        override val configuredTransport: String = "shell"
        override val observedConnected: Boolean = false
        override val availableForDispatch: Boolean = true

        override fun dispatch(
            operation: AutoServiceProtocol.Operation,
            generationCurrent: () -> Boolean
        ): AutoServiceProtocol.RawReply {
            if (!generationCurrent()) {
                return AutoServiceProtocol.RawReply.failed(
                    AutoServiceProtocol.Failure.UNSUPPORTED_MODE
                )
            }
            return when (operation.kind) {
                AutoServiceProtocol.Kind.LEGACY_GET_BUFFER ->
                    shellGetBytes(operation, generationCurrent)
                AutoServiceProtocol.Kind.LEGACY_GET_INT,
                AutoServiceProtocol.Kind.LEGACY_REMOTE_CONTROL,
                AutoServiceProtocol.Kind.LEGACY_SEND_CAN ->
                    shellGetInt(operation, generationCurrent)
                else -> AutoServiceProtocol.RawReply.failed(
                    AutoServiceProtocol.Failure.UNSUPPORTED_MODE
                )
            }
        }

        override fun close() = Unit
    }

    private fun shellGetInt(
        operation: AutoServiceProtocol.Operation,
        generationCurrent: () -> Boolean
    ): AutoServiceProtocol.RawReply {
        val output = shellServiceCall(operation, generationCurrent)
            ?: return AutoServiceProtocol.RawReply.failed(AutoServiceProtocol.Failure.SHELL_CALL)
        val match = parcelInt32Pattern.matcher(output)
        if (!match.find()) {
            return AutoServiceProtocol.RawReply.failed(AutoServiceProtocol.Failure.SHELL_CALL)
        }
        return try {
            AutoServiceProtocol.RawReply.words(
                java.lang.Long.parseLong(match.group(1)!!, 16).toInt()
            )
        } catch (_: Throwable) {
            AutoServiceProtocol.RawReply.failed(AutoServiceProtocol.Failure.SHELL_CALL)
        }
    }

    private fun shellGetBytes(
        operation: AutoServiceProtocol.Operation,
        generationCurrent: () -> Boolean
    ): AutoServiceProtocol.RawReply {
        val output = shellServiceCall(operation, generationCurrent)
            ?: return AutoServiceProtocol.RawReply.failed(AutoServiceProtocol.Failure.SHELL_CALL)
        val match = parcelByteHexPattern.matcher(output)
        val bytes = ArrayList<Byte>(64)
        while (match.find()) {
            try {
                bytes.add(java.lang.Integer.parseInt(match.group(1)!!, 16).toByte())
            } catch (_: Throwable) {
                return AutoServiceProtocol.RawReply.failed(AutoServiceProtocol.Failure.SHELL_CALL)
            }
        }
        return if (bytes.isEmpty()) {
            AutoServiceProtocol.RawReply.failed(AutoServiceProtocol.Failure.SHELL_CALL)
        } else {
            AutoServiceProtocol.RawReply.bytes(bytes.toByteArray())
        }
    }

    private fun shellServiceCall(
        operation: AutoServiceProtocol.Operation,
        generationCurrent: () -> Boolean
    ): String? {
        return try {
            val processBuilder = com.overdrive.app.util.ScratchRoot.applyEnv(
                ProcessBuilder(AutoServiceProtocol.shellCommand(SERVICE_NAME, operation))
            )
            if (!generationCurrent()) return null
            val process = processBuilder.start()
            val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            text.trim()
        } catch (_: Throwable) {
            null
        }
    }

    private fun log(message: String) {
        try {
            val loggerClass = Class.forName("com.overdrive.app.daemon.CameraDaemon")
            val logger = loggerClass.getMethod("log", String::class.java)
            logger.invoke(null, "$TAG: $message")
        } catch (_: Throwable) {
            android.util.Log.d(TAG, message)
        }
    }
}
