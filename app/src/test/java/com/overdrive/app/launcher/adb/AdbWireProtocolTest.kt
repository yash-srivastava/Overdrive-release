package com.overdrive.app.launcher.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbWireProtocolTest {
    @Test fun packetRoundTripPreservesClassicCnxnFrame() {
        val bytes = ByteArrayOutputStream()
        val packet = AdbWireProtocol.Packet(
            AdbWireProtocol.CNXN,
            AdbWireProtocol.VERSION,
            AdbWireProtocol.MAX_PAYLOAD,
            "host::\u0000".toByteArray(),
        )
        AdbWireProtocol.writePacket(bytes, packet)
        val decoded = AdbWireProtocol.readPacket(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(packet.command, decoded.command)
        assertEquals(packet.arg0, decoded.arg0)
        assertArrayEquals(packet.payload, decoded.payload)
    }

    @Test fun malformedMagicAndOversizedLengthFailBeforeAllocation() {
        val badMagic = header(AdbWireProtocol.CNXN, 0, 0, 0, 0, 0)
        assertFails { AdbWireProtocol.readPacket(ByteArrayInputStream(badMagic)) }
        val oversized = header(
            AdbWireProtocol.CNXN, 0, 0, AdbWireProtocol.MAX_PAYLOAD + 1,
            0, AdbWireProtocol.CNXN.inv(),
        )
        assertFails { AdbWireProtocol.readPacket(ByteArrayInputStream(oversized)) }
    }

    @Test fun checksumPolicyUsesNegotiatedVersion() {
        val payload = byteArrayOf(1)
        val frame = header(
            AdbWireProtocol.CNXN, 0, 0, payload.size, 99, AdbWireProtocol.CNXN.inv(),
        ) + payload
        assertFails { AdbWireProtocol.readPacket(ByteArrayInputStream(frame), 0x01000000) }
        assertArrayEquals(
            payload,
            AdbWireProtocol.readPacket(ByteArrayInputStream(frame), AdbWireProtocol.VERSION).payload,
        )
    }

    @Test fun correlatedStreamHasAnIndependentTotalByteLimit() {
        val inputBytes = ByteArrayOutputStream().apply {
            AdbWireProtocol.writePacket(
                this,
                AdbWireProtocol.Packet(AdbWireProtocol.OKAY, 7, 1, ByteArray(0)),
            )
            AdbWireProtocol.writePacket(
                this,
                AdbWireProtocol.Packet(AdbWireProtocol.WRTE, 7, 1, byteArrayOf(1)),
            )
        }.toByteArray()
        val result = AdbWireProtocol.openService(
            ByteArrayInputStream(inputBytes),
            ByteArrayOutputStream(),
            "tcpip:5555",
            AdbWireProtocol.VERSION,
            maxStreamBytes = 0,
        )
        assertTrue(result is AdbWireProtocol.StreamResult.Incomplete)
        assertEquals("stream_limit", (result as AdbWireProtocol.StreamResult.Incomplete).reason)
    }

    @Test fun serviceOpenUsesExactDestinationAndReportsIncompleteEof() {
        val output = ByteArrayOutputStream()
        val result = AdbWireProtocol.openService(
            ByteArrayInputStream(ByteArray(0)), output, "tcpip:5555", AdbWireProtocol.VERSION,
        )
        assertTrue(result is AdbWireProtocol.StreamResult.Incomplete)
        val open = AdbWireProtocol.readPacket(
            ByteArrayInputStream(output.toByteArray()), AdbWireProtocol.VERSION,
        )
        assertEquals(AdbWireProtocol.OPEN, open.command)
        assertEquals("tcpip:5555\u0000", String(open.payload))
        assertFails {
            AdbWireProtocol.openService(
                ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(),
                "shell:tcpip 5555", AdbWireProtocol.VERSION,
            )
        }
    }

    private fun header(command: Int, arg0: Int, arg1: Int, length: Int, checksum: Int, magic: Int) =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(checksum).putInt(magic).array()

    private fun assertFails(block: () -> Unit) {
        try { block(); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) { }
    }
}
