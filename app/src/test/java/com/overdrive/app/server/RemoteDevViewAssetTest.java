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
    public void pageRequiresCompactExplicitConfirmationForRealInput() throws Exception {
        String html = read("src/main/assets/web/local/remote-dev-view.html");

        assertTrue(html.contains("id=\"confirmStart\""));
        assertTrue(html.contains("Remote input operates the real Overdrive app"));
        assertTrue(html.contains("Use only while parked"));
        assertTrue(html.contains("id=\"startButton\" class=\"dev-button primary\" disabled"));
        assertFalse(html.contains("warning-card"));
    }

    @Test
    public void framesUseAuthenticatedPostAndNoStoreWithoutPuttingSessionInUrl() throws Exception {
        String script = read("src/main/assets/web/shared/remote-dev-view.js");
        String html = read("src/main/assets/web/local/remote-dev-view.html");

        assertTrue(script.contains("typeof BYDAuth === 'undefined'"));
        assertFalse(script.contains("window.BYDAuth"));
        assertTrue(script.contains("BYDAuth.requireAuth()"));
        assertTrue(script.contains("BYDAuth.fetch('/api/dev-view/frame'"));
        assertTrue(script.contains("method: 'POST'"));
        assertTrue(script.contains("cache: 'no-store'"));
        assertTrue(script.contains("body: JSON.stringify({ session: token"));
        assertFalse(script.contains("/api/dev-view/frame?"));
        assertFalse(script.contains("localStorage.setItem"));
        assertTrue(html.contains("remote-dev-view.js?v=3"));
    }

    @Test
    public void viewerOffersFullscreenWithAnInFrameEscapePath() throws Exception {
        String html = read("src/main/assets/web/local/remote-dev-view.html");
        String script = read("src/main/assets/web/shared/remote-dev-view.js");

        assertTrue(html.contains("id=\"viewerCard\""));
        assertTrue(html.contains("id=\"fullscreenButton\""));
        assertTrue(html.contains("id=\"fullscreenExitButton\""));
        assertTrue(script.contains("viewerCard.requestFullscreen"));
        assertTrue(script.contains("webkitRequestFullscreen"));
        assertTrue(script.contains("dev-view-focus"));
        assertTrue(script.contains("fullscreenStopButton"));
    }

    @Test
    public void deterrentActivityCannotReplaceOrConsumeTheRemoteTarget() throws Exception {
        String controller = read(
            "src/main/java/com/overdrive/app/remote/RemoteDevViewController.kt");

        assertTrue(controller.contains("activity !is DeterrentActivity"));
        assertTrue(controller.contains("if (isRemoteTarget(activity))"));
        assertTrue(controller.contains("activityOwnedRoots(activity)"));
        assertTrue(controller.contains("owner == null || owner === activity"));
    }

    @Test
    public void bridgeIsPrivateLoopbackAuthenticatedAndReplayBounded() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        String service = read(
            "src/main/java/com/overdrive/app/remote/RemoteDevViewBridgeService.kt");
        String auth = read(
            "src/main/java/com/overdrive/app/remote/RemoteDevViewBridgeAuth.java");

        assertTrue(manifest.contains("com.overdrive.app.remote.RemoteDevViewBridgeService"));
        assertTrue(manifest.contains("android:exported=\"false\""));
        assertTrue(service.contains("InetAddress.getLoopbackAddress()"));
        assertTrue(service.contains("const val PORT = 19881"));
        assertTrue(service.contains("rememberNonce(authenticated.nonce"));
        assertTrue(auth.contains("HmacSHA256"));
        assertTrue(auth.contains("MessageDigest.isEqual"));
        assertTrue(auth.contains("MAX_CLOCK_SKEW_MS"));

        String client = read(
            "src/main/java/com/overdrive/app/server/RemoteDevViewBridgeClient.java");
        assertTrue(client.contains("InetAddress.getLoopbackAddress()"));
        assertTrue(client.contains("RemoteDevViewBridgeAuth.sign(request)"));
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
