package com.overdrive.app.mqtt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.daemon.proxy.ProxyConfiguration;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Regression tests for sing-box (:8119) upstream SOCKS5 health verification,
 * ProxyConfiguration VLESS config generation, ABRP fallback routing, and
 * Tailscale log-path alignment.
 */
public class ProxyUpstreamHealthAndConfigTest {

    @Test
    public void socks5ConnectProbeDistinguishesHealthyFromDeadUpstream() throws Exception {
        byte[] target = new byte[] { 8, 8, 8, 8 };

        // Case 1: healthy SOCKS5 server (REP = 0x00 succeeded)
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread worker = new Thread(() -> {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    OutputStream out = client.getOutputStream();
                    byte[] greeting = new byte[3];
                    in.read(greeting);
                    out.write(new byte[] { 0x05, 0x00 });
                    out.flush();
                    byte[] req = new byte[10];
                    in.read(req);
                    out.write(new byte[] { 0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0 });
                    out.flush();
                } catch (Exception ignored) {
                }
            });
            worker.setDaemon(true);
            worker.start();
            assertTrue(ProxyHelper.probeSocks5Connect("127.0.0.1", port, target, 443, 500));
        }

        // Case 2: listening SOCKS5 server whose upstream fails (REP = 0x04 host unreachable)
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread worker = new Thread(() -> {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    OutputStream out = client.getOutputStream();
                    byte[] greeting = new byte[3];
                    in.read(greeting);
                    out.write(new byte[] { 0x05, 0x00 });
                    out.flush();
                    byte[] req = new byte[10];
                    in.read(req);
                    out.write(new byte[] { 0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0 });
                    out.flush();
                } catch (Exception ignored) {
                }
            });
            worker.setDaemon(true);
            worker.start();
            assertFalse(ProxyHelper.probeSocks5Connect("127.0.0.1", port, target, 443, 500));
        }

        // Case 3: listening port whose upstream hangs past timeout (dial tcp i/o timeout)
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread worker = new Thread(() -> {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    OutputStream out = client.getOutputStream();
                    byte[] greeting = new byte[3];
                    in.read(greeting);
                    out.write(new byte[] { 0x05, 0x00 });
                    out.flush();
                    Thread.sleep(400);
                } catch (Exception ignored) {
                }
            });
            worker.setDaemon(true);
            worker.start();
            assertFalse(ProxyHelper.probeSocks5Connect("127.0.0.1", port, target, 443, 150));
        }
    }

    @Test
    public void proxyConfigurationEmitsValidHardenedSingboxJsonWithoutPlaceholders() {
        String json = ProxyConfiguration.createConfig();
        assertFalse(json.contains("YOUR_SERVER_IP_HERE"));
        assertFalse(json.contains("YOUR_UUID_HERE"));
        assertFalse(json.contains("YOUR_SHORT_ID_HERE"));
        assertFalse(json.contains("YOUR_PUBLIC_KEY_HERE"));
        assertFalse(json.contains("\"ERR\""));
        assertFalse("UID 2000 cannot read packages.list", json.contains("override_android_vpn"));
        assertTrue(json.contains("\"address\": \"tcp://8.8.8.8\""));
        assertTrue(json.contains("\"10.0.0.0/8\""));
        assertTrue(json.contains("\"192.168.0.0/16\""));
    }

    @Test
    public void tailscaleDaemonHandlerAndAbrpRespectUpstreamHealthAndLogPaths() throws Exception {
        String handler = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java");
        assertFalse(handler.contains("tailscaled.log"));
        assertTrue(handler.contains("/data/local/tmp/.tailscale/tailscale.log"));
        assertTrue(handler.contains("socks5://127.0.0.1:8119"));
        assertTrue(handler.contains("ProxyHelper.isEgressProxyAvailable()"));

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
