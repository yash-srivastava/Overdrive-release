package com.overdrive.app.mqtt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.daemon.proxy.ProxyConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Regression tests for ProxyConfiguration VLESS config generation, ABRP
 * fallback routing, and Tailscale log-path alignment.
 */
public class ProxyUpstreamHealthAndConfigTest {

    @Test
    public void proxyConfigurationEmitsValidHardenedSingboxJsonWithoutPlaceholders() {
        String json = ProxyConfiguration.createConfig();
        assertFalse(json.contains("YOUR_SERVER_IP_HERE"));
        assertFalse(json.contains("YOUR_UUID_HERE"));
        assertFalse(json.contains("YOUR_SHORT_ID_HERE"));
        assertFalse(json.contains("YOUR_PUBLIC_KEY_HERE"));
        assertFalse(json.contains("\"ERR\""));
        assertTrue(json.contains("\"address\": \"tcp://8.8.8.8\""));
        assertTrue(json.contains("\"10.0.0.0/8\""));
        assertTrue(json.contains("\"192.168.0.0/16\""));
    }

    @Test
    public void tailscaleDaemonHandlerAndAbrpUseProxyRoutingAndLogPaths() throws Exception {
        String handler = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java");
        assertFalse(handler.contains("tailscaled.log"));
        assertTrue(handler.contains("/data/local/tmp/.tailscale/tailscale.log"));
        assertTrue(handler.contains("socks5://127.0.0.1:8119"));

        String abrp = read(
                "app/src/main/java/com/overdrive/app/abrp/AbrpTelemetryService.java");
        assertTrue(abrp.contains(".proxySelector(com.overdrive.app.mqtt.ProxyHelper.chainProxySelector())"));
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
