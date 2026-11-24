package com.overdrive.app.roadsense.sidecar

import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.os.MemoryFile

/**
 * Accel/gyro read through a [SensorDirectChannel] ring buffer, which is not bound by the
 * listener path's minDelay clamp (10 Hz on DiLink 5). Open, poll and close on one thread.
 */
internal class DiLink5DirectImu private constructor(
    private val mem: MemoryFile,
    private val channel: SensorDirectChannel,
    val level: Int,
    val accelToken: Int,
    val gyroToken: Int,
) {
    private val ring = DirectRingParser(
        { dst, srcOffset, count -> mem.readBytes(dst, srcOffset, 0, count) },
        accelToken, gyroToken,
    )
    private var closed = false

    val torn: Long get() = ring.torn
    val maxCounter: Long get() = ring.maxCounter
    val tokenMismatch: Int get() = ring.tokenMismatch
    val unattributed: Int get() = ring.unattributed

    /** Deliver records newer than the previous poll, oldest first. */
    fun poll(sink: DirectRingParser.Sink): Int = if (closed) 0 else ring.poll(sink)

    fun close() {
        if (closed) return
        closed = true
        closeQuietly(channel, mem)
    }

    class Opened(val imu: DiLink5DirectImu?, val detail: String)

    companion object {
        private const val SLOTS = DirectRingParser.SLOTS
        private const val RECORD_BYTES = DirectRingParser.RECORD_BYTES

        /** Open a channel at the fastest supported level up to FAST; [Opened.detail] says why not. */
        fun open(sm: SensorManager, accel: Sensor, gyro: Sensor?): Opened {
            if (!accel.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE)) {
                return Opened(null, "accel has no memfile direct channel")
            }
            val top = accel.highestDirectReportRateLevel
            if (top < SensorDirectChannel.RATE_NORMAL) return Opened(null, "accel direct level=$top")
            val mem = try {
                MemoryFile("roadsense-imu", SLOTS * RECORD_BYTES)
            } catch (t: Throwable) {
                return Opened(null, "MemoryFile ${describe(t)}")
            }
            val channel: SensorDirectChannel? = try {
                sm.createDirectChannel(mem)
            } catch (t: Throwable) {
                try { mem.close() } catch (_: Throwable) {}
                return Opened(null, "createDirectChannel ${describe(t)}")
            }
            if (channel == null) {
                try { mem.close() } catch (_: Throwable) {}
                return Opened(null, "createDirectChannel returned null")
            }
            val notes = StringBuilder()
            var level = minOf(top, SensorDirectChannel.RATE_FAST)
            var accelToken = configure(channel, accel, level, notes)
            if (accelToken <= 0 && level > SensorDirectChannel.RATE_NORMAL) {
                level = SensorDirectChannel.RATE_NORMAL
                accelToken = configure(channel, accel, level, notes)
            }
            if (accelToken <= 0) {
                closeQuietly(channel, mem)
                return Opened(null, "configure accel failed:$notes")
            }
            val gyroToken = if (gyro != null &&
                gyro.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE) &&
                gyro.highestDirectReportRateLevel >= level
            ) configure(channel, gyro, level, notes) else 0
            return Opened(
                DiLink5DirectImu(mem, channel, level, accelToken, gyroToken),
                "level=${levelName(level)} accelToken=$accelToken gyroToken=$gyroToken$notes",
            )
        }

        private fun configure(channel: SensorDirectChannel, sensor: Sensor, level: Int, notes: StringBuilder): Int =
            try {
                channel.configure(sensor, level).also {
                    if (it <= 0) notes.append(" t${sensor.type}@${levelName(level)}=$it")
                }
            } catch (t: Throwable) {
                notes.append(" t${sensor.type}@${levelName(level)} ${describe(t)}")
                0
            }

        private fun closeQuietly(channel: SensorDirectChannel, mem: MemoryFile) {
            try { channel.configure(null, SensorDirectChannel.RATE_STOP) } catch (_: Throwable) {}
            try { channel.close() } catch (_: Throwable) {}
            try { mem.close() } catch (_: Throwable) {}
        }

        fun levelName(level: Int): String = when (level) {
            SensorDirectChannel.RATE_NORMAL -> "NORMAL"
            SensorDirectChannel.RATE_FAST -> "FAST"
            SensorDirectChannel.RATE_VERY_FAST -> "VERY_FAST"
            else -> level.toString()
        }

        fun describe(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message}"
    }
}
