package com.overdrive.app.launcher.dilink5.cluster

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostedProjectionFailureTest {
    @Test
    fun androidReportsTheOriginalServiceErrorAndRedactsIntentDetails() {
        val failure = SecurityException("Unable to find app for caller {private details}\nsecond line")
        assertEquals("SecurityException: Unable to find app for caller [redacted]", hostedProjectionFailure(failure))
    }
}
