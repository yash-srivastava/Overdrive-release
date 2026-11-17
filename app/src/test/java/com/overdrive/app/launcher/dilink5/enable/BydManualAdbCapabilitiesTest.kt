package com.overdrive.app.launcher.dilink5.enable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BydManualAdbCapabilitiesTest {
    @Test
    fun aNonBydDefaultDeviceCannotAcquireManualOemRails() {
        assertEquals(
            BydManualAdbCapabilities.NONE,
            BydManualAdbCapabilities.fromPassiveEvidence(false, false, false, false),
        )
    }

    @Test
    fun knownBydMetadataAllowsAnExplicitSocketAttemptWithoutWakeReceiverMetadata() {
        val capabilities = BydManualAdbCapabilities.fromPassiveEvidence(false, false, false, true)

        assertTrue(capabilities.logTool)
        assertTrue(capabilities.developerPage)
        assertFalse(capabilities.accountFile)
        assertFalse(capabilities.strategy)
        assertFalse(capabilities.developerToolsExport)
    }

    @Test
    fun verifiedOemServiceMetadataDoesNotRequireALogToolReceiver() {
        val capabilities = BydManualAdbCapabilities.fromPassiveEvidence(true, false, false, false)

        assertTrue(capabilities.accountFile)
        assertTrue(capabilities.logTool)
        assertTrue(capabilities.canEnable)
    }
}
