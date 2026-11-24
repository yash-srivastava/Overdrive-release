package com.overdrive.app.roadsense.sidecar

import android.hardware.Sensor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for a sensor direct-channel ring (SENSOR_DIRECT_FMT_SENSORS_EVENT): 104-byte
 * little-endian records of size, report token, sensor type, atomic counter (written last;
 * 0 = never written), timestamp ns, data. Newness is per-sensor timestamp, so counter
 * resets and per-sensor counter schemes don't matter. Single-threaded.
 */
internal class DirectRingParser(
    private val reader: Reader,
    private val accelToken: Int,
    private val gyroToken: Int,
) {
    fun interface Reader {
        fun read(dst: ByteArray, srcOffset: Int, count: Int)
    }

    fun interface Sink {
        fun onSample(type: Int, timestampNs: Long, x: Float, y: Float, z: Float)
    }

    private val bulk = ByteArray(SLOTS * RECORD_BYTES)
    private val bulkBuf = ByteBuffer.wrap(bulk).order(ByteOrder.LITTLE_ENDIAN)
    private val record = ByteArray(RECORD_BYTES)
    private val recordBuf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
    private val pendingSlots = IntArray(SLOTS)
    private val pendingTs = LongArray(SLOTS)
    private var lastAccelNs = 0L
    private var lastGyroNs = 0L

    /** Records that changed between the bulk copy and the per-slot re-read. */
    var torn = 0L
        private set
    /** Highest atomic counter seen; 0 means nothing was ever written. */
    var maxCounter = 0L
        private set
    /** Written records in the last poll whose token matched neither configured sensor. */
    var tokenMismatch = 0
        private set
    /** Written records in the last poll that couldn't be attributed to accel or gyro. */
    var unattributed = 0
        private set

    fun poll(sink: Sink): Int {
        reader.read(bulk, 0, bulk.size)
        tokenMismatch = 0
        unattributed = 0
        var pending = 0
        for (slot in 0 until SLOTS) {
            val o = slot * RECORD_BYTES
            val counter = bulkBuf.getInt(o + OFF_COUNTER).toLong() and 0xffffffffL
            if (counter == 0L) continue
            if (counter > maxCounter) maxCounter = counter
            val token = bulkBuf.getInt(o + OFF_TOKEN)
            if (token != accelToken && !(gyroToken > 0 && token == gyroToken)) tokenMismatch++
            val type = kindOf(bulkBuf.getInt(o + OFF_TYPE), token)
            if (type == 0) {
                unattributed++
                continue
            }
            val ts = bulkBuf.getLong(o + OFF_TIMESTAMP)
            if (ts <= (if (type == Sensor.TYPE_ACCELEROMETER) lastAccelNs else lastGyroNs)) continue
            var i = pending++
            while (i > 0 && pendingTs[i - 1] > ts) {
                pendingTs[i] = pendingTs[i - 1]
                pendingSlots[i] = pendingSlots[i - 1]
                i--
            }
            pendingTs[i] = ts
            pendingSlots[i] = slot
        }
        var delivered = 0
        for (k in 0 until pending) {
            val o = pendingSlots[k] * RECORD_BYTES
            // Re-read after seeing the counter so the header and data are the completed write.
            reader.read(record, o, RECORD_BYTES)
            val ts = recordBuf.getLong(OFF_TIMESTAMP)
            if (ts != pendingTs[k] || recordBuf.getInt(OFF_COUNTER) != bulkBuf.getInt(o + OFF_COUNTER)) {
                torn++
                continue
            }
            val type = kindOf(recordBuf.getInt(OFF_TYPE), recordBuf.getInt(OFF_TOKEN))
            if (type == Sensor.TYPE_ACCELEROMETER) {
                if (ts <= lastAccelNs) continue
                lastAccelNs = ts
            } else if (type == Sensor.TYPE_GYROSCOPE) {
                if (ts <= lastGyroNs) continue
                lastGyroNs = ts
            } else {
                torn++
                continue
            }
            sink.onSample(
                type, ts,
                recordBuf.getFloat(OFF_DATA), recordBuf.getFloat(OFF_DATA + 4), recordBuf.getFloat(OFF_DATA + 8),
            )
            delivered++
        }
        return delivered
    }

    /** Type wins over token: some HALs write the sensor handle where the token belongs. */
    private fun kindOf(type: Int, token: Int): Int = when {
        type == Sensor.TYPE_ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
        type == Sensor.TYPE_GYROSCOPE && gyroToken > 0 -> Sensor.TYPE_GYROSCOPE
        token == accelToken -> Sensor.TYPE_ACCELEROMETER
        gyroToken > 0 && token == gyroToken -> Sensor.TYPE_GYROSCOPE
        else -> 0
    }

    companion object {
        const val SLOTS = 128
        const val RECORD_BYTES = 104
        private const val OFF_TOKEN = 4
        private const val OFF_TYPE = 8
        private const val OFF_COUNTER = 12
        private const val OFF_TIMESTAMP = 16
        private const val OFF_DATA = 24
    }
}
