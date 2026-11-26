package com.overdrive.app.ui.timeline.player

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SampleBufferPoolTest {
    @Test fun reusesSizeClassesWithoutAliasingBorrowedArrays() {
        val pool = SampleBufferPool()
        val first = pool.acquire(5000)
        val second = pool.acquire(6000)
        assertEquals(8192, first.size)
        assertNotSame(first, second)
        first[0] = 11
        second[0] = 22
        pool.recycle(first)
        val third = pool.acquire(8192)
        assertSame(first, third)
        third[0] = 33
        assertEquals(22, second[0].toInt())
        pool.recycle(second)
        pool.recycle(third)
        pool.close()
    }

    @Test fun copyUsesOnlyTheValidPayloadEvenWhenReusingLargerStorage() {
        val pool = SampleBufferPool()
        val old = pool.acquire(7000)
        old.fill(99)
        pool.recycle(old)
        val expected = ByteArray(5000) { (it % 251).toByte() }
        val scratch = ByteBuffer.allocateDirect(8192)
        scratch.put(expected)
        val data = pool.copyFrom(scratch, expected.size)
        assertSame(old, data)
        assertEquals(expected.size, scratch.position())
        val sample = Sample(1, data, expected.size, 17L, 0, null)
        assertEquals(8192, sample.storageBytes)
        val codecInput = ByteBuffer.allocate(expected.size)
        codecInput.put(data, 0, sample.size)
        pool.recycle(data)
        // A subsequent feeder write must not change the already copied codec bytes.
        val next = pool.acquire(expected.size)
        assertSame(data, next)
        next.fill(42)
        assertArrayEquals(expected, codecInput.array())
        assertEquals(0, Sample(1, null, 0, 0L, 0, null).storageBytes)
    }

    @Test fun idleByteCapEvictsOldestAndLargeSamplesAreNotRetained() {
        val pool = SampleBufferPool()
        val arrays = List(5) { pool.acquire(SampleBufferPool.MAX_POOLED_ARRAY) }
        arrays.forEach { pool.recycle(it) }
        for (i in 4 downTo 1) assertSame(arrays[i], pool.acquire(SampleBufferPool.MAX_POOLED_ARRAY))
        assertNotSame(arrays[0], pool.acquire(SampleBufferPool.MAX_POOLED_ARRAY))
        val large = pool.acquire(SampleBufferPool.MAX_POOLED_ARRAY + 1)
        assertEquals(SampleBufferPool.MAX_POOLED_ARRAY + 1, large.size)
        pool.recycle(large)
        assertNotSame(large, pool.acquire(large.size))
    }

    @Test fun idleCountCapAndCloseRejectLateReturns() {
        val pool = SampleBufferPool()
        val arrays = List(SampleBufferPool.MAX_IDLE_ARRAYS + 1) { pool.acquire(1) }
        arrays.forEach { pool.recycle(it) }
        for (i in arrays.lastIndex downTo 1) assertSame(arrays[i], pool.acquire(1))
        val borrowed = pool.acquire(1)
        assertNotSame(arrays[0], borrowed)
        pool.recycle(arrays[1])
        pool.close()
        pool.recycle(borrowed)
        val fresh = pool.acquire(1)
        assertEquals(1, fresh.size)
        assertNotSame(borrowed, fresh)
        assertNotSame(arrays[1], fresh)
    }

    @Test fun steadyStateReusesAllWarmedBuffers() {
        val pool = SampleBufferPool()
        val sizes = listOf(3000, 25000, 150000)
        val warmed = sizes.map { pool.acquire(it) }
        warmed.forEach { pool.recycle(it) }
        repeat(1000) {
            val i = it % sizes.size
            val data = pool.acquire(sizes[i])
            assertSame(warmed[i], data)
            pool.recycle(data)
        }
    }
}
