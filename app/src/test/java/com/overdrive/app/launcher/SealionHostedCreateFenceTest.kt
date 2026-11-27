package com.overdrive.app.launcher

import com.overdrive.app.launcher.dilink5.cluster.SealionCreateFence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SealionHostedCreateFenceTest {
    @Test
    fun rejectsMissingExpiredAndExcessiveDeadlines() {
        val fence = SealionCreateFence<String>(3_000)
        assertEquals("invalid or expired deadline", fence.rejection("daemon", 1, 0, 10_000))
        assertEquals("invalid or expired deadline", fence.rejection("daemon", 1, 10_000, 10_000))
        assertEquals("invalid or expired deadline", fence.rejection("daemon", 1, 13_001, 10_000))
        assertNull(fence.rejection("daemon", 1, 13_000, 10_000))
    }

    @Test
    fun releaseBeforeCreateRejectsOnlyThatGenerationForTheRequestWindow() {
        val fence = SealionCreateFence<String>(3_000)
        fence.retire("daemon", 5, 10_000)
        assertEquals("generation already released", fence.rejection("daemon", 5, 13_000, 10_001))
        assertNull(fence.rejection("daemon", 6, 13_000, 10_001))
        assertNull(fence.rejection("replacement daemon", 5, 13_000, 10_001))
        assertEquals("invalid or expired deadline", fence.rejection("daemon", 5, 13_000, 13_000))
        assertNull(fence.rejection("daemon", 5, 16_000, 13_000))
    }
}
