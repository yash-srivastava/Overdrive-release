package com.overdrive.app.launcher.adb

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded ADB packet codec and correlated single-stream exchange. */
object AdbWireProtocol {
    const val VERSION = 0x01000001
    const val STLS_VERSION = 0x01000000
    const val MAX_PAYLOAD = 262_144
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257
    const val STLS = 0x534c5453
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_PUBLIC_KEY = 3

    data class Packet(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    sealed class StreamResult {
        data class Complete(val payload: ByteArray) : StreamResult()
        data class Incomplete(val payload: ByteArray, val reason: String) : StreamResult()
    }

    fun readPacket(input: InputStream, negotiatedVersion: Int? = null): Packet {
        val header = readExactly(input, 24)
        val values = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = values.int
        val arg0 = values.int
        val arg1 = values.int
        val length = values.int
        val checksum = values.int
        val magic = values.int
        require(magic == command.inv()) { "Invalid ADB packet magic" }
        require(length in 0..MAX_PAYLOAD) { "Invalid ADB payload length: $length" }
        val payload = readExactly(input, length)
        if (negotiatedVersion == null || negotiatedVersion < VERSION) {
            require(checksum(payload) == checksum) { "Invalid ADB payload checksum" }
        }
        return Packet(command, arg0, arg1, payload)
    }

    fun writePacket(output: OutputStream, packet: Packet) {
        require(packet.payload.size <= MAX_PAYLOAD) { "ADB payload is too large" }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(packet.command)
            .putInt(packet.arg0)
            .putInt(packet.arg1)
            .putInt(packet.payload.size)
            .putInt(checksum(packet.payload))
            .putInt(packet.command.inv())
            .array()
        output.write(header)
        output.write(packet.payload)
        output.flush()
    }

    fun openService(
        input: InputStream,
        output: OutputStream,
        destination: String,
        negotiatedVersion: Int,
        maxPackets: Int = 500,
        maxStreamBytes: Int = MAX_STREAM_BYTES,
        beforeIo: () -> Unit = {},
        onOpenDispatching: () -> Unit = {},
    ): StreamResult {
        require(destination == "tcpip:5555") { "Unexpected recovery service destination" }
        val localId = 1
        beforeIo()
        // Mark before the write because a write failure can follow partial transmission.
        onOpenDispatching()
        writePacket(output, Packet(OPEN, localId, 0, (destination + "\u0000").toByteArray()))
        var remoteId = 0
        val collected = ArrayList<Byte>()
        repeat(maxPackets) {
            val packet = try {
                beforeIo()
                readPacket(input, negotiatedVersion)
            } catch (_: EOFException) {
                return StreamResult.Incomplete(collected.toByteArray(), "eof")
            } catch (_: SocketTimeoutException) {
                return StreamResult.Incomplete(collected.toByteArray(), "timeout")
            }
            when (packet.command) {
                OKAY -> {
                    require(packet.arg1 == localId) { "Uncorrelated ADB OKAY" }
                    if (remoteId == 0) remoteId = packet.arg0
                    require(packet.arg0 == remoteId) { "ADB remote stream changed" }
                }
                WRTE -> {
                    require(packet.arg1 == localId && packet.arg0 == remoteId && remoteId != 0) {
                        "Uncorrelated ADB WRTE"
                    }
                    if (collected.size + packet.payload.size > maxStreamBytes) {
                        return StreamResult.Incomplete(collected.toByteArray(), "stream_limit")
                    }
                    packet.payload.forEach { collected.add(it) }
                    beforeIo()
                    writePacket(output, Packet(OKAY, localId, remoteId, ByteArray(0)))
                }
                CLSE -> {
                    require(packet.arg1 == localId && packet.arg0 == remoteId && remoteId != 0) {
                        "Uncorrelated ADB CLSE"
                    }
                    beforeIo()
                    writePacket(output, Packet(CLSE, localId, remoteId, ByteArray(0)))
                    return StreamResult.Complete(collected.toByteArray())
                }
                else -> return StreamResult.Incomplete(collected.toByteArray(), "unexpected_packet")
            }
        }
        return StreamResult.Incomplete(collected.toByteArray(), "packet_limit")
    }

    private const val MAX_STREAM_BYTES = 1_048_576

    fun checksum(payload: ByteArray): Int = payload.fold(0) { sum, byte -> sum + (byte.toInt() and 0xff) }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read < 0) throw EOFException("ADB packet ended early")
            if (read == 0) continue
            offset += read
        }
        return result
    }
}
