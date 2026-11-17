package com.overdrive.app.launcher.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DiLink5AdbRecoveryDiscoveryTest {
    @Test fun onlyAnAddressOwnedByTheIdentifiedLocalNetworkIsAccepted() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val foreign = InetAddress.getByName("192.0.2.10")
        val network = DiLink5AdbRecoveryDiscovery.NetworkIdentity("wifi-1", setOf(loopback))
        assertTrue(DiLink5AdbRecoveryDiscovery.isCandidateAllowed(loopback, network))
        assertFalse(DiLink5AdbRecoveryDiscovery.isCandidateAllowed(foreign, network))
    }

    @Test fun unidentifiedNetworksFailClosedAndStableIdentitySurvivesInstances() {
        assertTrue(DiLink5AdbRecoveryDiscovery.stableNetworkId("<unknown ssid>", "aa:bb:cc:dd:ee:ff") == null)
        assertTrue(DiLink5AdbRecoveryDiscovery.stableNetworkId("car", "02:00:00:00:00:00") == null)
        val first = DiLink5AdbRecoveryDiscovery.stableNetworkId("\"car\"", "AA:BB:CC:DD:EE:FF")
        val second = DiLink5AdbRecoveryDiscovery.stableNetworkId("car", "aa:bb:cc:dd:ee:ff")
        assertTrue(first == second && first?.length == 64)
    }

    @Test fun redactedAndroidElevenWifiGetsABootScopedFallbackIdentity() {
        val addresses = setOf(InetAddress.getByName("192.0.2.20"))
        val first = DiLink5AdbRecoveryDiscovery.ephemeralNetworkId(17L, "wlan0", addresses)
        val second = DiLink5AdbRecoveryDiscovery.ephemeralNetworkId(17L, "wlan0", addresses)
        val other = DiLink5AdbRecoveryDiscovery.ephemeralNetworkId(18L, "wlan0", addresses)
        assertTrue(first.startsWith("ephemeral:"))
        assertTrue(first == second)
        assertFalse(first == other)
    }

    @Test fun serviceTypeIsTheTlsConnectService() {
        assertTrue(DiLink5AdbRecoveryDiscovery.SERVICE_TYPE == "_adb-tls-connect._tcp.")
    }
}
