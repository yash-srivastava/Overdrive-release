package com.overdrive.app.launcher.dilink5.enable

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5LogToolProtocolTest {
    private val protocol = DiLink5LogToolProtocol(null)

    @Test
    fun shortAndLongFramesUseExactNetworkOrderHeaders() {
        val shortBody = "hello".toByteArray()
        val shortFrame = protocol.encodeFrame(1, shortBody)
        assertEquals(1, shortFrame[0].toInt())
        assertEquals(5, shortFrame[1].toInt())
        assertArrayEquals(shortBody, shortFrame.copyOfRange(2, shortFrame.size))

        val longBody = ByteArray(300) { (it and 0xff).toByte() }
        val longFrame = protocol.encodeFrame(0, longBody)
        assertEquals(2, longFrame[0].toInt())
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 1, 44), longFrame.copyOfRange(1, 9))
        assertArrayEquals(longBody, longFrame.copyOfRange(9, longFrame.size))
    }

    @Test
    fun fragmentedReadsConsumeExactlyOneHeaderAndBody() {
        val expected = ByteArray(300) { (255 - it).toByte() }
        val encoded = protocol.encodeFrame(1, expected)
        val fragmented = object : InputStream() {
            private val delegate = ByteArrayInputStream(encoded)
            override fun read(): Int = delegate.read()
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                delegate.read(target, offset, minOf(1, length))
        }
        val frame = protocol.readFrame(fragmented)
        assertTrue(frame.more)
        assertFalse(frame.command)
        assertArrayEquals(expected, frame.body)
        assertEquals(-1, fragmented.read())
    }

    @Test(expected = DiLink5LogToolProtocol.ProtocolException::class)
    fun slowFragmentationCannotOutliveOverallDeadline() {
        var now = 0L
        val timedProtocol = DiLink5LogToolProtocol(null, clockMs = { now })
        val encoded = timedProtocol.encodeFrame(0, byteArrayOf(1, 2, 3, 4))
        val slow = object : InputStream() {
            private val delegate = ByteArrayInputStream(encoded)
            override fun read(): Int = delegate.read().also { now++ }
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                delegate.read(target, offset, minOf(1, length)).also { now++ }
        }

        timedProtocol.readFrame(slow, 4L, DiLink5LogToolProtocol.Cancellation { false })
    }

    @Test(expected = DiLink5LogToolProtocol.CancelledException::class)
    fun cancellationIsRecheckedBetweenBodyFragments() {
        var cancelled = false
        val encoded = protocol.encodeFrame(0, byteArrayOf(1, 2, 3))
        val fragmented = object : InputStream() {
            private val delegate = ByteArrayInputStream(encoded)
            override fun read(): Int = delegate.read()
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                val read = delegate.read(target, offset, minOf(1, length))
                if (read > 0) cancelled = true
                return read
            }
        }

        protocol.readFrame(
            fragmented,
            Long.MAX_VALUE,
            DiLink5LogToolProtocol.Cancellation { cancelled },
        )
    }

    @Test
    fun readTimeoutIsCappedToRemainingOverallBudget() {
        val timeouts = mutableListOf<Int>()
        val timedProtocol = DiLink5LogToolProtocol(null, clockMs = { 750L })

        timedProtocol.readFrame(
            ByteArrayInputStream(byteArrayOf(0, 0)),
            1_000L,
            DiLink5LogToolProtocol.Cancellation { false },
            timeouts::add,
        )

        assertTrue(timeouts.isNotEmpty())
        assertTrue(timeouts.all { it in 1..250 })
    }

    @Test(expected = DiLink5LogToolProtocol.ProtocolException::class)
    fun oversizedFrameIsRejectedBeforeAllocation() {
        val oversizedHeader = byteArrayOf(2, 0, 0, 0, 0, 0, 1, 0, 1)
        protocol.readFrame(ByteArrayInputStream(oversizedHeader))
    }

    @Test(expected = DiLink5LogToolProtocol.ProtocolException::class)
    fun unknownFlagsAreRejected() {
        protocol.readFrame(ByteArrayInputStream(byteArrayOf(8, 0)))
    }

    @Test(expected = java.io.EOFException::class)
    fun truncatedFragmentedBodyFailsClosed() {
        protocol.readFrame(ByteArrayInputStream(byteArrayOf(0, 3, 1, 2)))
    }

    @Test
    fun correlatedRepliesRemainUnconfirmedWithoutAnObservedServerSchema() {
        listOf(
            "{\"msgId\":\"42\",\"success\":true}",
            "{\"msgId\":\"42\",\"success\":false}",
            "{\"msgId\":\"42\",\"error\":\"denied\"}",
            "{\"msgId\":\"42\",\"status\":\"ok\"}",
        ).forEach { reply ->
            val result = protocol.parseReply("42", reply)
            assertEquals(DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY, result.outcome)
            assertTrue(result.replyCorrelated)
        }
        val unrelated = protocol.parseReply("42", "{\"msgId\":\"41\",\"success\":true}")
        assertEquals(DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY, unrelated.outcome)
        assertFalse(unrelated.replyCorrelated)
    }

    @Test
    fun receiptCorrelationNeverAcceptsMalformedUnrelatedOrIncompleteObjects() {
        listOf(
            "not-json",
            "{}",
            "{\"msgId\":\"41\"}",
            "{\"response\":{\"msgId\":\"42\"}}",
            "{\"msgId\":\"42\",\"msgId\":\"42\"}",
            "{\"msgId\":{\"value\":\"42\"}}",
            "{\"msgId\":\"42\"} trailing",
        ).forEach { reply ->
            assertFalse(reply, protocol.parseReply("42", reply).replyCorrelated)
        }
    }

    @Test
    fun pongDropsPingTtlAndEchoesOnlyContext() {
        val ping = byteArrayOf(4) + "PING".toByteArray() +
            byteArrayOf(0x12, 0x34, 9, 8, 7)

        assertArrayEquals(
            byteArrayOf(4) + "PONG".toByteArray() + byteArrayOf(9, 8, 7),
            DiLink5LogToolProtocol.pongBody(ping),
        )
    }

    @Test
    fun malformedNestedDuplicateAndWrongTypeRepliesFailClosed() {
        val unsupported = listOf(
            "not-json",
            "{\"response\":{\"msgId\":\"42\",\"success\":true}}",
            "{\"msgId\":\"42\",\"msgId\":\"42\",\"success\":true}",
            "{\"msgId\":{\"value\":\"42\"},\"success\":true}",
            "{\"msgId\":\"42\",\"success\":\"true\"}",
            "{\"msgId\":\"42\",\"success\":true,\"error\":false}",
            "{\"msgId\":\"42\",\"success\":true} trailing",
        )

        unsupported.forEach { reply ->
            assertEquals(
                reply,
                DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY,
                protocol.parseReply("42", reply).outcome,
            )
        }
    }

    @Test
    fun productionWakeRetriesUseOperationSpecificCadencesAndRemainCancellable() {
        assertEquals(
            listOf(600L, 1_200L, 1_800L),
            DiLink5LogToolProtocol.PROBE_STARTUP_SETTLE_CADENCE_MS.toList(),
        )
        assertEquals(
            listOf(500L, 1_000L, 1_500L),
            DiLink5LogToolProtocol.SEND_STARTUP_SETTLE_CADENCE_MS.toList(),
        )
        var now = 0L
        var sockets = 0
        val attemptTimes = mutableListOf<Long>()
        val tested = DiLink5LogToolProtocol(
            null,
            socketFactory = {
                sockets++
                attemptTimes += now
                object : ProtocolSocket(ByteArray(0)) {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        throw java.net.ConnectException("not ready")
                    }
                }
            },
            clockMs = { now },
            sleeper = { delay -> now += delay },
            wakeSender = { },
        )

        assertEquals(DiLink5LogToolProtocol.Availability.UNAVAILABLE, tested.probe())
        assertEquals(3, sockets)
        assertEquals(listOf(600L, 1_800L, 3_600L), attemptTimes)

        now = 0L
        sockets = 0
        attemptTimes.clear()
        val sendProtocol = DiLink5LogToolProtocol(
            null,
            socketFactory = {
                sockets++
                attemptTimes += now
                object : ProtocolSocket(ByteArray(0)) {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        throw java.net.ConnectException("not ready")
                    }
                }
            },
            clockMs = { now },
            sleeper = { delay -> now += delay },
            wakeSender = { },
        )
        assertEquals(DiLink5LogToolProtocol.Outcome.UNAVAILABLE, sendProtocol.setAdbTcpPort().outcome)
        assertEquals(3, sockets)
        assertEquals(listOf(500L, 1_500L, 3_000L), attemptTimes)

        var cancelled = false
        now = 0L
        val cancelledProtocol = DiLink5LogToolProtocol(
            null,
            socketFactory = { throw AssertionError("cancelled wait must not open a socket") },
            clockMs = { now },
            sleeper = { delay -> now += delay; cancelled = true },
            wakeSender = { },
        )
        assertEquals(
            DiLink5LogToolProtocol.Availability.CANCELLED,
            cancelledProtocol.probe(DiLink5LogToolProtocol.Cancellation { cancelled }),
        )
    }

    @Test
    fun cancellationBetweenFailedConnectAttemptsIsTypedAndOpensNoSecondSocket() {
        var cancelled = false
        var sockets = 0
        var now = 0L
        val tested = DiLink5LogToolProtocol(
            null,
            socketFactory = {
                sockets++
                object : ProtocolSocket(ByteArray(0)) {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        cancelled = true
                        throw java.net.ConnectException("not ready")
                    }
                }
            },
            clockMs = { now },
            sleeper = { delay -> now += delay },
            wakeSender = { },
        )

        assertEquals(
            DiLink5LogToolProtocol.Availability.CANCELLED,
            tested.probe(DiLink5LogToolProtocol.Cancellation { cancelled }),
        )
        assertEquals(1, sockets)
    }

    @Test
    fun cancellationAtTheLastFailedConnectionIsStillTypedForProbeAndSend() {
        listOf(true, false).forEach { probe ->
            var cancelled = false
            var sockets = 0
            val tested = DiLink5LogToolProtocol(
                null,
                socketFactory = {
                    sockets++
                    object : ProtocolSocket(ByteArray(0)) {
                        override fun connect(endpoint: SocketAddress?, timeout: Int) {
                            if (sockets == 3) cancelled = true
                            throw java.net.ConnectException("not ready")
                        }
                    }
                },
                clockMs = { 0L },
            )
            val cancellation = DiLink5LogToolProtocol.Cancellation { cancelled }
            if (probe) {
                assertEquals(DiLink5LogToolProtocol.Availability.CANCELLED, tested.probe(cancellation))
            } else {
                val result = tested.setAdbTcpPort(cancellation)
                assertEquals(DiLink5LogToolProtocol.Outcome.CANCELLED, result.outcome)
                assertFalse(result.requestSent)
            }
            assertEquals(3, sockets)
        }
    }

    @Test
    fun startupSettleDeadlineMapsToTypedUnavailable() {
        var now = 0L
        val tested = DiLink5LogToolProtocol(
            null,
            socketFactory = { throw AssertionError("expired settle must not open a socket") },
            clockMs = { now },
            overallTimeoutMs = 250L,
            sleeper = { delay -> now += delay },
            wakeSender = { },
        )

        assertEquals(DiLink5LogToolProtocol.Availability.UNAVAILABLE, tested.probe())
        assertEquals(DiLink5LogToolProtocol.Outcome.UNAVAILABLE, tested.setAdbTcpPort().outcome)
    }

    @Test
    fun interruptedStartupSettleMapsToTypedCancellation() {
        val tested = DiLink5LogToolProtocol(
            null,
            socketFactory = { throw AssertionError("interrupted settle must not open a socket") },
            clockMs = { 0L },
            sleeper = { throw InterruptedException("cancel") },
            wakeSender = { },
        )

        assertEquals(DiLink5LogToolProtocol.Availability.CANCELLED, tested.probe())
        // Clear the interrupt status so the second independent request can exercise its own wait.
        Thread.interrupted()
        assertEquals(DiLink5LogToolProtocol.Outcome.CANCELLED, tested.setAdbTcpPort().outcome)
        Thread.interrupted()
    }

    @Test
    fun wakeBroadcastFailureStillUsesAnAlreadyRunningEndpoint() {
        lateinit var tested: DiLink5LogToolProtocol
        var now = 0L
        tested = DiLink5LogToolProtocol(
            null,
            socketFactory = { ProtocolSocket(handshakeBytes(tested)) },
            clockMs = { now },
            sleeper = { delay -> now += delay },
            wakeSender = { throw SecurityException("broadcast unavailable") },
        )

        assertEquals(DiLink5LogToolProtocol.Availability.AVAILABLE, tested.probe())
    }

    @Test
    fun preCancelledRequestCreatesNoSocket() {
        var sockets = 0
        val tested = DiLink5LogToolProtocol(
            null,
            socketFactory = {
                sockets++
                ProtocolSocket(ByteArray(0))
            },
        )

        val result = tested.setAdbTcpPort(DiLink5LogToolProtocol.Cancellation { true })

        assertEquals(DiLink5LogToolProtocol.Outcome.CANCELLED, result.outcome)
        assertEquals(0, sockets)
    }

    @Test
    fun requestIsNeverRetransmittedAfterPayloadWriteWithoutReply() {
        var sockets = 0
        lateinit var tested: DiLink5LogToolProtocol
        tested = DiLink5LogToolProtocol(
            null,
            socketFactory = {
                sockets++
                ProtocolSocket(handshakeBytes(tested))
            },
            clockMs = { 0L },
        )

        val result = tested.setAdbTcpPort()

        assertEquals(1, sockets)
        assertTrue(result.requestSent)
        assertEquals(DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY, result.outcome)
    }

    @Test
    fun blockedWriteIsClosedAtOverallDeadlineWithoutRetransmission() {
        val origin = System.nanoTime()
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        var sockets = 0
        var closes = 0
        val output = object : OutputStream() {
            var writes = 0
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writes++
                if (writes == 3) {
                    writeStarted.countDown()
                    releaseWrite.await()
                }
            }
        }
        lateinit var tested: DiLink5LogToolProtocol
        tested = DiLink5LogToolProtocol(
            null,
            socketFactory = {
                sockets++
                object : ProtocolSocket(handshakeBytes(tested), output) {
                    override fun close() {
                        closes++
                        releaseWrite.countDown()
                    }
                }
            },
            clockMs = {
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin)
            },
            overallTimeoutMs = 200L,
        )

        val result = tested.setAdbTcpPort()

        assertTrue(writeStarted.await(1, TimeUnit.SECONDS))
        assertEquals(1, sockets)
        assertTrue(closes >= 1)
        assertTrue(result.requestSent)
        assertEquals(DiLink5LogToolProtocol.Outcome.UNKNOWN_REPLY, result.outcome)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin) < 2_000L)
    }

    @Test
    fun retriesCapConnectTimeoutToRemainingOverallDeadline() {
        var now = 0L
        val timeouts = mutableListOf<Int>()
        val tested = DiLink5LogToolProtocol(
            null,
            socketFactory = {
                object : ProtocolSocket(ByteArray(0)) {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        timeouts += timeout
                        if (timeouts.size == 1) now = 7_900L
                        throw java.net.SocketTimeoutException()
                    }
                }
            },
            clockMs = { now },
        )

        assertEquals(DiLink5LogToolProtocol.Availability.UNAVAILABLE, tested.probe())
        assertEquals(1_500, timeouts.first())
        assertTrue(timeouts.drop(1).all { it in 1..100 })
    }

    private fun handshakeBytes(protocol: DiLink5LogToolProtocol): ByteArray {
        val greeting = ByteArray(64).apply {
            this[0] = 0xff.toByte()
            this[9] = 0x7f
            this[10] = 3
        }
        val ready = byteArrayOf(5) + "READY".toByteArray()
        return greeting + protocol.encodeFrame(4, ready)
    }

    private open class ProtocolSocket(
        input: ByteArray,
        private val sink: OutputStream = ByteArrayOutputStream(),
    ) : Socket() {
        private val source = ByteArrayInputStream(input)

        override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
        override fun getInputStream() = source
        override fun getOutputStream() = sink
        override fun close() = Unit
    }
}
