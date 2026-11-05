package com.overdrive.app.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Regression tests for run-2 findings
 * CellularRelay.kt:0.0.0.0:8121:unauthenticated-open-proxy and
 * ProxyConfiguration.kt:0.0.0.0:8122:unauthenticated-clients-in-inbound.
 */
class CellularRelaySecurityTest {

    @Test
    fun destinationDenylistBlocksPrivateAndLocal() {
        val relay = CellularRelay
        for (bad in listOf("127.0.0.1", "0.0.0.0", "10.1.2.3", "192.168.43.1", "172.16.5.5",
                "169.254.1.1", "224.0.0.1", "::1", "fd00::1", "fe80::1")) {
            assertFalse("must block $bad", relay.isAllowedDestination(InetAddress.getByName(bad)))
        }
        for (ok in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")) {
            assertTrue("must allow $ok", relay.isAllowedDestination(InetAddress.getByName(ok)))
        }
    }

    @Test
    fun authTokenIsRequiredAndConstantTimeCompared() {
        val relay = CellularRelay
        val old = relay.authToken
        try {
            relay.authToken = "s3cret-token"
            assertTrue(relay.authTokenMatches("s3cret-token"))
            assertFalse(relay.authTokenMatches("s3cret-tokem"))
            assertFalse(relay.authTokenMatches(null))
            assertFalse(relay.authTokenMatches(""))
            relay.authToken = null
            assertTrue(relay.authTokenMatches("anything"))
        } finally {
            relay.authToken = old
        }
    }

    @Test
    fun relayNeverBindsWildcard() {
        val s = String(Files.readAllBytes(Paths.get(
            "src/main/java/com/overdrive/app/network/CellularRelay.kt")), StandardCharsets.UTF_8)
        assertFalse(s.contains("InetSocketAddress(\"0.0.0.0\""))
        assertTrue(s.contains("apGatewayAddress()") && s.contains("fail closed"))
        assertTrue(s.contains("Proxy-Authorization:"))
        assertTrue(s.contains("0x02 else 0xFF"))
    }

    @Test
    fun singboxClientsInboundBindsCallerAddressAndRequiresUsers() {
        val s = String(Files.readAllBytes(Paths.get(
            "src/main/java/com/overdrive/app/daemon/proxy/ProxyConfiguration.kt")), StandardCharsets.UTF_8)
        assertFalse("no wildcard listen constant may remain", s.contains("ANY_LISTEN"))
        assertTrue(s.contains("\"listen\": \"\$clientBind\""))
        assertTrue(s.contains("\"users\": [ { \"username\":"))
        val d = String(Files.readAllBytes(Paths.get(
            "src/main/java/com/overdrive/app/daemon/GlobalProxyDaemon.java")), StandardCharsets.UTF_8)
        assertFalse(d.contains("return \"0.0.0.0\""))
    }
}
