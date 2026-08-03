package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RemoteDevViewAssetTest {

    @Test
    public void pageRequiresExplicitConfirmationAndWarnsAboutRealInput() throws Exception {
        String html = read("src/main/assets/web/local/remote-dev-view.html");

        assertTrue(html.contains("id=\"confirmStart\""));
        assertTrue(html.contains("remote input operates the live Overdrive app"));
        assertTrue(html.contains("physical head-unit display is not turned on or uncovered"));
        assertTrue(html.contains("id=\"startButton\" class=\"dev-button primary\" disabled"));
    }

    @Test
    public void framesUseAuthenticatedPostAndNoStoreWithoutPuttingSessionInUrl() throws Exception {
        String script = read("src/main/assets/web/shared/remote-dev-view.js");

        assertTrue(script.contains("BYDAuth.requireAuth()"));
        assertTrue(script.contains("BYDAuth.fetch('/api/dev-view/frame'"));
        assertTrue(script.contains("method: 'POST'"));
        assertTrue(script.contains("cache: 'no-store'"));
        assertTrue(script.contains("body: JSON.stringify({ session: token"));
        assertFalse(script.contains("/api/dev-view/frame?"));
        assertFalse(script.contains("localStorage.setItem"));
    }

    @Test
    public void bridgeIsPrivateAndAuthenticatesKernelPeerUid() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        String service = read(
            "src/main/java/com/overdrive/app/remote/RemoteDevViewBridgeService.kt");

        assertTrue(manifest.contains("com.overdrive.app.remote.RemoteDevViewBridgeService"));
        assertTrue(service.contains("LocalServerSocket(SOCKET_NAME)"));
        assertTrue(service.contains("client.peerCredentials.uid"));
        assertTrue(service.contains("peerUid != SHELL_UID"));

        String client = read(
            "src/main/java/com/overdrive/app/server/RemoteDevViewBridgeClient.java");
        assertTrue(client.contains("new LocalSocket()"));
        assertTrue(client.contains("LocalSocketAddress.Namespace.ABSTRACT"));
        assertFalse(client.contains("Runtime.getRuntime().exec"));
        assertFalse(client.contains("new ProcessBuilder("));
    }

    @Test
    public void developerViewIsNotOnAutomationAuthBypassAllowlist() throws Exception {
        String server = read("src/main/java/com/overdrive/app/server/HttpServer.java");
        int allowlistStart = server.indexOf("AUTOMATION_ALLOWED_PREFIXES");
        int allowlistEnd = server.indexOf("};", allowlistStart);
        String allowlist = server.substring(allowlistStart, allowlistEnd);

        assertFalse(allowlist.contains("dev-view"));
        assertTrue(server.contains("path.startsWith(\"/api/dev-view/\")"));
    }

    private static String read(String moduleRelativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path fromModule = current.resolve(moduleRelativePath);
        if (Files.exists(fromModule)) {
            return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
        }
        Path fromRepository = current.resolve("app").resolve(moduleRelativePath);
        if (Files.exists(fromRepository)) {
            return new String(Files.readAllBytes(fromRepository), StandardCharsets.UTF_8);
        }
        throw new AssertionError("Could not locate file: " + moduleRelativePath);
    }
}
