package com.overdrive.app.byd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoServiceProtocolTest {
    @Test
    fun legacyOperationsKeepExactTransactionsAndArgumentOrder() {
        assertOperation(
            AutoServiceProtocol.Operation.legacyGetInt(12, -3),
            6,
            AutoServiceProtocol.ReplyEncoding.RAW_INT,
            listOf(12, -3)
        )
        assertOperation(
            AutoServiceProtocol.Operation.legacyGetBuffer(7, 9),
            8,
            AutoServiceProtocol.ReplyEncoding.RAW_BUFFER,
            listOf(7, 9)
        )
        assertOperation(
            AutoServiceProtocol.Operation.legacyRemoteControl(17),
            40,
            AutoServiceProtocol.ReplyEncoding.EXCEPTION_INT,
            listOf(5, 17)
        )
        val can = AutoServiceProtocol.Operation.legacySendCan("0x123 01 02")
        assertEquals(42, can.transaction)
        assertEquals(AutoServiceProtocol.ReplyEncoding.EXCEPTION_INT, can.replyEncoding)
        assertEquals(AutoServiceProtocol.Argument.IntValue(0), can.arguments[0])
        assertEquals(AutoServiceProtocol.Argument.StringValue("0x123 01 02"), can.arguments[1])
        assertEquals(AutoServiceProtocol.LEGACY_INTERFACE_TOKEN, "android.gui.BYDAutoServer")
    }

    @Test
    fun typedOperationsKeepTransactionAndDeviceFidOrder() {
        assertOperation(
            AutoServiceProtocol.Operation.typedInt(1013, -1807745016),
            5,
            AutoServiceProtocol.ReplyEncoding.STATUS_AND_VALUE,
            listOf(1013, -1807745016)
        )
        assertOperation(
            AutoServiceProtocol.Operation.typedFloat(1004, 950009900),
            7,
            AutoServiceProtocol.ReplyEncoding.STATUS_AND_VALUE,
            listOf(1004, 950009900)
        )
    }

    @Test
    fun integerDecoderPreservesZeroAndSignedValuesButMarksKnownSentinels() {
        val zero = AutoServiceProtocol.decodeTyped(AutoServiceProtocol.RawReply.words(0, 0), false)!!
        assertEquals(0, zero.status)
        assertEquals(0, zero.integerValue)
        assertFalse(zero.sentinel)
        assertTrue(zero.readable)

        val signed = AutoServiceProtocol.decodeTyped(
            AutoServiceProtocol.RawReply.words(0, -123456), false
        )!!
        assertEquals(-123456, signed.integerValue)
        assertFalse(signed.sentinel)

        for (sentinel in intArrayOf(65535, 1048575, -10013, -10011)) {
            val decoded = AutoServiceProtocol.decodeTyped(
                AutoServiceProtocol.RawReply.words(0, sentinel), false
            )!!
            assertTrue(decoded.sentinel)
            assertNull(decoded.integerValue)
            assertFalse(decoded.readable)
        }
    }

    @Test
    fun floatDecoderSeparatesRawBitsSentinelsAndPlausibility() {
        val negative = AutoServiceProtocol.decodeTyped(
            AutoServiceProtocol.RawReply.words(0, (-12.5f).toBits()), true
        )!!
        assertEquals(-12.5f, negative.floatValue!!, 0f)
        assertFalse(negative.sentinel)
        assertEquals(false, AutoServiceProtocol.plausibleFloat(negative, 0f, 400f))

        val zero = AutoServiceProtocol.decodeTyped(
            AutoServiceProtocol.RawReply.words(0, 0f.toBits()), true
        )!!
        assertEquals(0f, zero.floatValue!!, 0f)
        assertEquals(true, AutoServiceProtocol.plausibleFloat(zero, 0f, 400f))

        for (value in floatArrayOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val decoded = AutoServiceProtocol.decodeTyped(
                AutoServiceProtocol.RawReply.words(0, value.toBits()), true
            )!!
            assertTrue(decoded.sentinel)
            assertNull(decoded.floatValue)
            assertNull(AutoServiceProtocol.plausibleFloat(decoded, 0f, 400f))
        }
    }

    @Test
    fun malformedAndFailedRepliesDoNotDecode() {
        assertNull(AutoServiceProtocol.decodeTyped(AutoServiceProtocol.RawReply.words(), false))
        assertNull(AutoServiceProtocol.decodeTyped(AutoServiceProtocol.RawReply.words(0), false))
        assertNull(AutoServiceProtocol.decodeTyped(
            AutoServiceProtocol.RawReply.failed(AutoServiceProtocol.Failure.TRANSACT_FALSE),
            false
        ))
        assertNull(AutoServiceProtocol.decodeTyped(
            AutoServiceProtocol.RawReply(bytes = byteArrayOf(1, 2)),
            false
        ))
    }

    @Test
    fun byteRepliesAreDefensivelyCopiedByFixtures() {
        val input = byteArrayOf(1, 2, 3)
        val reply = AutoServiceProtocol.RawReply.bytes(input)
        input[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), reply.bytes)
    }

    @Test
    fun productionBinderWriterKeepsTokenAndExactArgumentOrder() {
        val writer = RecordingWireWriter()
        AutoServiceProtocol.writeBinderEnvelope(
            AutoServiceProtocol.LEGACY_INTERFACE_TOKEN,
            AutoServiceProtocol.Operation.legacySendCan("raw frame with spaces;$(literal)"),
            writer
        )

        assertEquals(
            listOf(
                "token:android.gui.BYDAutoServer",
                "int:0",
                "string:raw frame with spaces;$(literal)"
            ),
            writer.values
        )

        writer.values.clear()
        AutoServiceProtocol.writeBinderEnvelope(
            "typed.descriptor",
            AutoServiceProtocol.Operation.typedFloat(1004, -33),
            writer
        )
        assertEquals(
            listOf("token:typed.descriptor", "int:1004", "int:-33"),
            writer.values
        )
    }

    @Test
    fun productionShellCommandPreservesLegacyFramesAsOneArgumentWithoutShellParsing() {
        val frame = """ 0x123 01 02; echo unchanged "quoted" """
        assertEquals(
            listOf("service", "call", "autoservice", "42", "i32", "0", "s16", frame),
            AutoServiceProtocol.shellCommand(
                "autoservice",
                AutoServiceProtocol.Operation.legacySendCan(frame)
            )
        )
        assertEquals(
            listOf("service", "call", "autoservice", "40", "i32", "5", "i32", "17"),
            AutoServiceProtocol.shellCommand(
                "autoservice",
                AutoServiceProtocol.Operation.legacyRemoteControl(17)
            )
        )
    }

    private class RecordingWireWriter : AutoServiceProtocol.WireWriter {
        val values = mutableListOf<String>()

        override fun writeInterfaceToken(value: String) {
            values += "token:$value"
        }

        override fun writeInt(value: Int) {
            values += "int:$value"
        }

        override fun writeString(value: String) {
            values += "string:$value"
        }
    }

    private fun assertOperation(
        operation: AutoServiceProtocol.Operation,
        transaction: Int,
        reply: AutoServiceProtocol.ReplyEncoding,
        integers: List<Int>
    ) {
        assertEquals(transaction, operation.transaction)
        assertEquals(reply, operation.replyEncoding)
        assertEquals(
            integers,
            operation.arguments.map { (it as AutoServiceProtocol.Argument.IntValue).value }
        )
    }
}
