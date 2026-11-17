package com.overdrive.app.launcher.dilink5.enable

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.json.JSONTokener

/** Outgoing loopback-only client for the OEM log-tool ZMTP endpoint. */
class DiLink5LogToolProtocol(
    private val context: Context?,
    private val socketFactory: () -> Socket = { Socket() },
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val overallTimeoutMs: Long = OVERALL_TIMEOUT_MS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val wakeSender: (() -> Unit)? = null,
) {
    fun interface Cancellation { fun isCancelled(): Boolean }

    enum class Availability { AVAILABLE, UNAVAILABLE, CANCELLED }
    enum class Outcome { CONFIRMED, REJECTED, UNKNOWN_REPLY, UNAVAILABLE, CANCELLED }

    data class RequestResult(
        val outcome: Outcome,
        val reply: String? = null,
        val detail: String,
        val requestSent: Boolean = false,
        /** A complete matching reply arrived; this does not establish mutation success. */
        val replyCorrelated: Boolean = false,
    )

    fun probe(cancellation: Cancellation = Cancellation { false }): Availability {
        val settleForStartup = try {
            wake(cancellation)
        } catch (_: CancelledException) {
            return Availability.CANCELLED
        }
        return try {
            repeatConnect(cancellation, settleForStartup) { socket, deadline ->
                handshake(socket, deadline, cancellation)
                Availability.AVAILABLE
            } ?: Availability.UNAVAILABLE
        } catch (_: CancelledException) {
            Availability.CANCELLED
        } catch (_: ProtocolException) {
            Availability.UNAVAILABLE
        }
    }

    fun setAdbTcpPort(cancellation: Cancellation = Cancellation { false }): RequestResult =
        send("SET_PROPERTY", propertyData(), cancellation)

    fun requestDeveloperActivity(cancellation: Cancellation = Cancellation { false }): RequestResult =
        send("OPEN_ACTIVITY", activityData(), cancellation)

    private fun send(action: String, data: String, cancellation: Cancellation): RequestResult {
        val settleForStartup = try {
            wake(cancellation)
        } catch (_: CancelledException) {
            return RequestResult(Outcome.CANCELLED, null, "cancelled")
        }
        val id = SEQUENCE.incrementAndGet().toString()
        val payload = "{\"msgId\":\"$id\",\"from\":\"PC\",\"to\":\"USER10\",\"type\":\"CMD\",\"action\":\"$action\",\"data\":$data,\"token\":\"$TOKEN\"}"
        val overallDeadline = clockMs() + overallTimeoutMs
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (cancellation.isCancelled()) {
                return RequestResult(Outcome.CANCELLED, null, "cancelled")
            }
            if (clockMs() >= overallDeadline) {
                return RequestResult(Outcome.UNAVAILABLE, null, "loopback endpoint unavailable")
            }
            if (settleForStartup) {
                try {
                    waitForConnectAttempt(
                        attempt,
                        overallDeadline,
                        cancellation,
                        SEND_STARTUP_SETTLE_CADENCE_MS,
                    )
                } catch (_: CancelledException) {
                    return RequestResult(Outcome.CANCELLED, null, "cancelled")
                } catch (_: ProtocolException) {
                    return RequestResult(Outcome.UNAVAILABLE, null, "loopback endpoint unavailable")
                }
            }
            val socket = socketFactory()
            var requestSent = false
            try {
                connect(socket, overallDeadline, cancellation)
                handshake(socket, overallDeadline, cancellation)
                val output = socket.getOutputStream()
                checkActive(overallDeadline, cancellation)
                requestSent = true
                writeBounded(
                    socket,
                    output,
                    encodeFrame(FLAG_MORE, ByteArray(0)),
                    overallDeadline,
                    cancellation,
                )
                writeBounded(
                    socket,
                    output,
                    encodeFrame(0, payload.toByteArray(StandardCharsets.UTF_8)),
                    overallDeadline,
                    cancellation,
                )
                checkActive(overallDeadline, cancellation)
                val reply = readReply(socket, socket.getInputStream(), output, overallDeadline, cancellation) {
                    socket.soTimeout = it
                } ?: return RequestResult(
                    Outcome.UNKNOWN_REPLY,
                    null,
                    "request was sent but no supported reply was received",
                    true,
                )
                return parseReply(id, reply).copy(requestSent = true)
            } catch (_: CancelledException) {
                return RequestResult(
                    Outcome.CANCELLED,
                    null,
                    if (requestSent) "cancelled after request transmission" else "cancelled",
                    requestSent,
                )
            } catch (error: Throwable) {
                if (cancellation.isCancelled()) {
                    return RequestResult(
                        Outcome.CANCELLED,
                        null,
                        if (requestSent) "cancelled after request transmission" else "cancelled",
                        requestSent,
                    )
                }
                if (requestSent) {
                    return RequestResult(
                        Outcome.UNKNOWN_REPLY,
                        null,
                        "request was sent but confirmation was unavailable (${error.javaClass.simpleName})",
                        true,
                    )
                }
                if (attempt == CONNECT_ATTEMPTS - 1) {
                    return RequestResult(Outcome.UNAVAILABLE, null, "loopback endpoint unavailable")
                }
            } finally {
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
            }
        }
        return RequestResult(Outcome.UNAVAILABLE, null, "loopback endpoint unavailable")
    }

    private fun <T> repeatConnect(
        cancellation: Cancellation,
        settleForStartup: Boolean,
        body: (Socket, Long) -> T,
    ): T? {
        val overallDeadline = clockMs() + overallTimeoutMs
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (cancellation.isCancelled()) throw CancelledException()
            if (clockMs() >= overallDeadline) return null
            if (settleForStartup) {
                waitForConnectAttempt(
                    attempt,
                    overallDeadline,
                    cancellation,
                    PROBE_STARTUP_SETTLE_CADENCE_MS,
                )
            }
            val socket = socketFactory()
            try {
                connect(socket, overallDeadline, cancellation)
                return body(socket, overallDeadline)
            } catch (cancelled: CancelledException) {
                throw cancelled
            } catch (_: Throwable) {
                if (cancellation.isCancelled()) throw CancelledException()
                if (attempt == CONNECT_ATTEMPTS - 1) return null
            } finally {
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    private fun connect(socket: Socket, deadline: Long, cancellation: Cancellation) {
        checkActive(deadline, cancellation)
        val remaining = deadline - clockMs()
        if (remaining <= 0) throw ProtocolException("overall timeout")
        socket.connect(
            InetSocketAddress(LOOPBACK, PORT),
            remaining.coerceAtMost(CONNECT_TIMEOUT_MS.toLong()).coerceAtLeast(1L).toInt(),
        )
        checkActive(deadline, cancellation)
        socket.soTimeout = (deadline - clockMs())
            .coerceAtMost(READ_TIMEOUT_MS.toLong())
            .coerceAtLeast(1L)
            .toInt()
    }

    /** Returns true when a production wake was attempted; failure never blocks socket probing. */
    private fun wake(cancellation: Cancellation): Boolean {
        if (cancellation.isCancelled()) throw CancelledException()
        if (wakeSender != null) {
            try { wakeSender.invoke() } catch (_: Throwable) {}
            return true
        }
        val target = context ?: return false
        val intent = Intent(WAKE_ACTION)
            .setComponent(ComponentName(LOG_TOOL_PACKAGE, LOG_TOOL_RECEIVER))
            .addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES)
        if (cancellation.isCancelled()) throw CancelledException()
        try {
            target.sendBroadcast(intent)
        } catch (_: Throwable) {
            // The endpoint may already be running. Reference behavior treats wake as best effort.
        }
        return true
    }

    private fun waitForConnectAttempt(
        attempt: Int,
        deadline: Long,
        cancellation: Cancellation,
        cadenceMs: LongArray,
    ) {
        val requested = cadenceMs[attempt]
        val waitUntil = (clockMs() + requested).coerceAtMost(deadline)
        while (clockMs() < waitUntil) {
            if (cancellation.isCancelled()) throw CancelledException()
            val remaining = waitUntil - clockMs()
            if (remaining <= 0L) break
            try {
                sleeper(remaining.coerceAtMost(STARTUP_POLL_MS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancelledException()
            }
        }
        checkActive(deadline, cancellation)
    }

    private fun handshake(socket: Socket, deadline: Long, cancellation: Cancellation) {
        checkActive(deadline, cancellation)
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        writeBounded(socket, output, clientGreeting(), deadline, cancellation)
        val peer = readExact(input, GREETING_BYTES, MAX_GREETING_BYTES, deadline, cancellation) {
            socket.soTimeout = it
        }
        if (peer[0] != 0xff.toByte() || peer[9] != 0x7f.toByte() || peer[10] != 3.toByte()) {
            throw ProtocolException("unsupported greeting")
        }
        writeBounded(socket, output, encodeFrame(FLAG_COMMAND, readyBody()), deadline, cancellation)
        val ready = readFrame(input, deadline, cancellation) { socket.soTimeout = it }
        if (!ready.command || !commandName(ready.body).contentEquals("READY".toByteArray(StandardCharsets.US_ASCII))) {
            throw ProtocolException("missing READY")
        }
        checkActive(deadline, cancellation)
    }

    private fun readReply(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        deadline: Long,
        cancellation: Cancellation,
        updateReadTimeout: (Int) -> Unit,
    ): String? {
        while (clockMs() < deadline) {
            checkActive(deadline, cancellation)
            val frame = readFrame(input, deadline, cancellation, updateReadTimeout)
            if (frame.command) {
                if (commandName(frame.body).contentEquals("PING".toByteArray(StandardCharsets.US_ASCII))) {
                    writeBounded(
                        socket,
                        output,
                        encodeFrame(FLAG_COMMAND, pongBody(frame.body)),
                        deadline,
                        cancellation,
                    )
                }
                continue
            }
            if (frame.body.isEmpty()) continue
            if (frame.more) throw ProtocolException("unexpected multipart payload")
            return String(frame.body, StandardCharsets.UTF_8)
        }
        return null
    }

    private fun writeBounded(
        socket: Socket,
        output: OutputStream,
        bytes: ByteArray,
        deadline: Long,
        cancellation: Cancellation,
    ) {
        checkActive(deadline, cancellation)
        val write = WRITE_EXECUTOR.submit {
            output.write(bytes)
            output.flush()
        }
        try {
            while (true) {
                checkActive(deadline, cancellation)
                val remaining = deadline - clockMs()
                if (remaining <= 0L) throw ProtocolException("overall timeout")
                try {
                    write.get(
                        remaining.coerceAtMost(WRITE_POLL_MS).coerceAtLeast(1L),
                        TimeUnit.MILLISECONDS,
                    )
                    checkActive(deadline, cancellation)
                    return
                } catch (_: TimeoutException) {
                    // Recheck cancellation and the operation deadline between bounded waits.
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
            }
        } finally {
            if (!write.isDone) {
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
                write.cancel(true)
            }
        }
    }

    private fun checkActive(deadline: Long, cancellation: Cancellation) {
        if (cancellation.isCancelled()) throw CancelledException()
        if (clockMs() >= deadline) throw ProtocolException("overall timeout")
    }

    internal data class Frame(val more: Boolean, val command: Boolean, val body: ByteArray)

    internal fun readFrame(input: InputStream): Frame =
        readFrame(input, Long.MAX_VALUE, Cancellation { false }) { }

    internal fun readFrame(input: InputStream, deadline: Long, cancellation: Cancellation): Frame =
        readFrame(input, deadline, cancellation) { }

    internal fun readFrame(
        input: InputStream,
        deadline: Long,
        cancellation: Cancellation,
        updateReadTimeout: (Int) -> Unit,
    ): Frame {
        val flags = readByte(input, deadline, cancellation, updateReadTimeout, "frame closed")
        if (flags and ALLOWED_FLAGS.inv() != 0) throw ProtocolException("unsupported flags")
        val length = if (flags and FLAG_LONG != 0) {
            val bytes = readExact(input, 8, 8, deadline, cancellation, updateReadTimeout)
            var value = 0L
            bytes.forEach { byte ->
                if (value > (MAX_FRAME_BYTES.toLong() ushr 8)) throw ProtocolException("frame too large")
                value = (value shl 8) or (byte.toLong() and 0xffL)
            }
            value
        } else {
            readByte(input, deadline, cancellation, updateReadTimeout, "frame length closed").toLong()
        }
        if (length > MAX_FRAME_BYTES || length > Int.MAX_VALUE) {
            throw ProtocolException("frame too large")
        }
        return Frame(
            more = flags and FLAG_MORE != 0,
            command = flags and FLAG_COMMAND != 0,
            body = readExact(
                input,
                length.toInt(),
                MAX_FRAME_BYTES,
                deadline,
                cancellation,
                updateReadTimeout,
            ),
        )
    }

    private fun readByte(
        input: InputStream,
        deadline: Long,
        cancellation: Cancellation,
        updateReadTimeout: (Int) -> Unit,
        closedMessage: String,
    ): Int {
        prepareRead(deadline, cancellation, updateReadTimeout)
        val value = input.read()
        checkAfterRead(deadline, cancellation)
        if (value < 0) throw EOFException(closedMessage)
        return value
    }

    private fun readExact(
        input: InputStream,
        count: Int,
        limit: Int,
        deadline: Long,
        cancellation: Cancellation,
        updateReadTimeout: (Int) -> Unit,
    ): ByteArray {
        if (count < 0 || count > limit) throw ProtocolException("allocation rejected")
        val output = ByteArray(count)
        var offset = 0
        while (offset < count) {
            prepareRead(deadline, cancellation, updateReadTimeout)
            val read = input.read(output, offset, count - offset)
            checkAfterRead(deadline, cancellation)
            if (read < 0) throw EOFException("stream closed")
            if (read == 0) continue
            offset += read
        }
        return output
    }

    private fun prepareRead(
        deadline: Long,
        cancellation: Cancellation,
        updateReadTimeout: (Int) -> Unit,
    ) {
        if (cancellation.isCancelled()) throw CancelledException()
        val remaining = if (deadline == Long.MAX_VALUE) {
            READ_TIMEOUT_MS.toLong()
        } else {
            val now = clockMs()
            if (now >= deadline) throw ProtocolException("overall timeout")
            (deadline - now).coerceAtMost(READ_TIMEOUT_MS.toLong())
        }
        updateReadTimeout(remaining.coerceAtLeast(1L).toInt())
    }

    private fun checkAfterRead(deadline: Long, cancellation: Cancellation) {
        if (cancellation.isCancelled()) throw CancelledException()
        if (deadline != Long.MAX_VALUE && clockMs() >= deadline) {
            throw ProtocolException("overall timeout")
        }
    }

    internal fun encodeFrame(flags: Int, body: ByteArray): ByteArray {
        require(flags and ALLOWED_FLAGS.inv() == 0) { "unsupported flags" }
        require(body.size <= MAX_FRAME_BYTES) { "frame too large" }
        val output = ByteArrayOutputStream(body.size + 9)
        if (body.size < 255) {
            output.write(flags and FLAG_LONG.inv())
            output.write(body.size)
        } else {
            output.write(flags or FLAG_LONG)
            val length = body.size.toLong()
            for (shift in 56 downTo 0 step 8) output.write(((length ushr shift) and 0xff).toInt())
        }
        output.write(body)
        return output.toByteArray()
    }

    internal fun parseReply(expectedId: String, reply: String): RequestResult {
        val parsed = try {
            val tokener = JSONTokener(reply)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != '\u0000') {
                return RequestResult(Outcome.UNKNOWN_REPLY, reply, "reply is not one complete object")
            }
            value
        } catch (_: Throwable) {
            return RequestResult(Outcome.UNKNOWN_REPLY, reply, "reply is malformed")
        }
        if (!parsed.has("msgId")) {
            return RequestResult(Outcome.UNKNOWN_REPLY, reply, "reply has no correlation id")
        }
        val id = when (val raw = parsed.opt("msgId")) {
            is String -> raw
            is Number -> raw.toString()
            else -> return RequestResult(Outcome.UNKNOWN_REPLY, reply, "reply correlation id has unsupported type")
        }
        if (id != expectedId) return RequestResult(Outcome.UNKNOWN_REPLY, reply, "reply id does not match")

        // No captured server contract establishes success/error field semantics. A correlated,
        // well-formed object proves only that a reply arrived; mutation success remains unknown
        // until an independent state observation establishes it.
        return RequestResult(
            Outcome.UNKNOWN_REPLY,
            reply,
            "correlated reply received; endpoint semantics are unverified",
            replyCorrelated = true,
        )
    }

    internal class ProtocolException(message: String) : Exception(message)
    internal class CancelledException : Exception()

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PORT = 5320
        const val WAKE_ACTION = "action.system.START_SOCKET_SERVER"
        const val LOG_TOOL_PACKAGE = "com.byd.bydlogtool"
        const val LOG_TOOL_RECEIVER = "com.byd.bydlogtool.LogReceiver"
        const val TOKEN = "BYD_LOGTOOL_2026"
        const val MAX_FRAME_BYTES = 64 * 1024
        private const val GREETING_BYTES = 64
        private const val MAX_GREETING_BYTES = 64
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val READ_TIMEOUT_MS = 2_500
        private const val OVERALL_TIMEOUT_MS = 8_000L
        private const val WRITE_POLL_MS = 25L
        internal val PROBE_STARTUP_SETTLE_CADENCE_MS = longArrayOf(600L, 1_200L, 1_800L)
        internal val SEND_STARTUP_SETTLE_CADENCE_MS = longArrayOf(500L, 1_000L, 1_500L)
        private const val STARTUP_POLL_MS = 50L
        private val CONNECT_ATTEMPTS = PROBE_STARTUP_SETTLE_CADENCE_MS.size
        private const val FLAG_MORE = 0x01
        private const val FLAG_LONG = 0x02
        private const val FLAG_COMMAND = 0x04
        private const val ALLOWED_FLAGS = FLAG_MORE or FLAG_LONG or FLAG_COMMAND
        private val SEQUENCE = AtomicLong(System.currentTimeMillis())
        private val WRITE_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "DiLink5LogToolWrite").apply { isDaemon = true }
        }

        private fun propertyData() = "{\"name\":\"service.adb.tcp.port\",\"value\":\"5555\"}"
        private fun activityData() = "{\"packageName\":\"com.byd.byddevelopmenttools\",\"activityName\":\"com.byd.byddevelopmenttools.LogControlAndTestToolsActivity\"}"

        private fun clientGreeting(): ByteArray = ByteArray(GREETING_BYTES).apply {
            this[0] = 0xff.toByte()
            this[9] = 0x7f
            this[10] = 3
            "NULL".toByteArray(StandardCharsets.US_ASCII).copyInto(this, 12)
        }

        private fun readyBody(): ByteArray = ByteArrayOutputStream().apply {
            write(5)
            write("READY".toByteArray(StandardCharsets.US_ASCII))
            writeMetadata(this, "Socket-Type", "DEALER")
            writeMetadata(this, "Identity", "probe")
        }.toByteArray()

        private fun writeMetadata(output: ByteArrayOutputStream, name: String, value: String) {
            val nameBytes = name.toByteArray(StandardCharsets.US_ASCII)
            val valueBytes = value.toByteArray(StandardCharsets.US_ASCII)
            output.write(nameBytes.size)
            output.write(nameBytes)
            output.write((valueBytes.size ushr 24) and 0xff)
            output.write((valueBytes.size ushr 16) and 0xff)
            output.write((valueBytes.size ushr 8) and 0xff)
            output.write(valueBytes.size and 0xff)
            output.write(valueBytes)
        }

        private fun commandName(body: ByteArray): ByteArray {
            if (body.isEmpty()) return ByteArray(0)
            val size = body[0].toInt() and 0xff
            if (size > body.size - 1) return ByteArray(0)
            return body.copyOfRange(1, 1 + size)
        }

        internal fun pongBody(ping: ByteArray): ByteArray {
            if (commandName(ping).decodeToString() != "PING" || ping.size < 7) {
                throw ProtocolException("invalid PING")
            }
            // PING carries a two-byte TTL immediately after the command name. PONG echoes only
            // the remaining context, never the TTL.
            return byteArrayOf(4) + "PONG".toByteArray(StandardCharsets.US_ASCII) +
                ping.copyOfRange(7, ping.size)
        }
    }
}
