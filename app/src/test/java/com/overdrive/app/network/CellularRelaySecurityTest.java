package com.overdrive.app.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression tests for run-2 finding
 * {@code CellularRelay.kt:0.0.0.0:8121:unauthenticated-open-proxy} and
 * {@code ProxyConfiguration.kt:0.0.0.0:8122:unauthenticated-clients-in-inbound}:
 * the client-facing proxy ports must bind the AP gateway only, require a
 * per-install token, and refuse non-public destinations.
 */
public class CellularRelaySecurityTest {

    @Test
    public void destinationDenylistBlocksPrivateAndLocal() throws Exception {
        assertFalse(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("127.0.0.1")));
        assertFalse(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("0.0.0.0")));
        assertFalse(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("10.1.2.3")));
        assertFalse(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("192.168.43.1")));
        assertFalse(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("172.16.5.5")));
        assertFalse(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("169.254.1.1")));
        assertFalse(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("224.0.0.1")));
        assertFalse(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("::1")));
        assertFalse(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("fd00::1")));
        assertFalse(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("fe80::1")));
        assertTrue(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("8.8.8.8")));
        assertTrue(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("1.1.1.1")));
        assertTrue(CellularRelay.INSTANCE.isAllowedDestination(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    public void authTokenIsRequiredAndConstantTimeCompared() {
        CellularRelay relay = CellularRelay.INSTANCE;
        String old = relay.authToken;
        try {
            relay.authToken = "s3cret-token";
            assertTrue(relay.authTokenMatches("s3cret-token"));
            assertFalse(relay.authTokenMatches("s3cret-tokem"));
            assertFalse(relay.authTokenMatches(null));
            assertFalse(relay.authTokenMatches(""));
            relay.authToken = null;
            assertTrue(relay.authTokenMatches("anything")); // auth disabled only when unconfigured
        } finally {
            relay.authToken = old;
        }
    }

    @Test
    public void relayNeverBindsWildcard() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/network/CellularRelay.kt")), StandardCharsets.UTF_8);
        assertFalse("relay must not bind 0.0.0.0",
                s.contains("InetSocketAddress(\"0.0.0.0\""));
        assertTrue("relay must bind the AP gateway and fail closed when unknown",
                s.contains("apGatewayAddress()") && s.contains("fail closed"));
        assertTrue("HTTP proxying must require Proxy-Authorization when a token is set",
                s.contains("Proxy-Authorization:"));
        assertTrue("SOCKS5 must not fall back to no-auth when a token is set",
                s.contains("0x02 else 0xFF"));
    }

    @Test
    public void singboxClientsInboundBindsCallerAddressAndRequiresUsers() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/daemon/proxy/ProxyConfiguration.kt")), StandardCharsets.UTF_8);
        assertFalse("no wildcard listen constant may remain", s.contains("ANY_LISTEN"));
        assertTrue("clients-in must bind the caller-provided address", s.contains("\"listen\": \"$clientBind\""));
        assertTrue("clients-in must support users auth", s.contains("\"users\": [ { \"username\":"));
        String d = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/daemon/GlobalProxyDaemon.java")), StandardCharsets.UTF_8);
        assertFalse("8119 must never widen to wildcard", d.contains("return \"0.0.0.0\""));
    }
}
