package com.overdrive.app.ui.timeline.player

import java.nio.ByteBuffer

/** Per-decoder pool of idle compressed video sample arrays. Never shares a borrowed array. */
internal class SampleBufferPool {
    private val idle = ArrayList<ByteArray>(MAX_IDLE_ARRAYS)
    private var idleBytes = 0
    private var closed = false

    /** Copy exactly [size] bytes from the start of the feeder's scratch buffer. */
    fun copyFrom(source: ByteBuffer, size: Int): ByteArray {
        require(size >= 0 && size <= source.limit())
        val data = acquire(size)
        source.position(0)
        source.get(data, 0, size)
        return data
    }

    @Synchronized
    fun acquire(size: Int): ByteArray {
        require(size >= 0)
        // Rare large keyframes stay exact-sized and are never retained by the pool.
        if (closed || size == 0 || size > MAX_POOLED_ARRAY) return ByteArray(size)
        var capacity = MIN_POOLED_ARRAY
        while (capacity < size) capacity *= 2
        for (i in idle.lastIndex downTo 0) {
            val data = idle[i]
            if (data.size == capacity) {
                idle.removeAt(i)
                idleBytes -= data.size
                return data
            }
        }
        return ByteArray(capacity)
    }

    /** The caller must no longer read or write [data] after returning it. */
    @Synchronized
    fun recycle(data: ByteArray?) {
        if (closed || data == null || data.size < MIN_POOLED_ARRAY || data.size > MAX_POOLED_ARRAY) return
        // Prefer the recently used sizes; bound both object count and retained capacity.
        while (idle.size >= MAX_IDLE_ARRAYS || idleBytes + data.size > MAX_IDLE_BYTES) {
            idleBytes -= idle.removeAt(0).size
        }
        idle.add(data)
        idleBytes += data.size
    }

    @Synchronized
    fun close() {
        closed = true
        idle.clear()
        idleBytes = 0
    }

    companion object {
        const val MIN_POOLED_ARRAY = 4 * 1024
        const val MAX_POOLED_ARRAY = 512 * 1024
        const val MAX_IDLE_ARRAYS = 12
        const val MAX_IDLE_BYTES = 2 * 1024 * 1024
    }
}
