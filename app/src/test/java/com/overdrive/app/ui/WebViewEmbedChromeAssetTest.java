package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Pins the WebView first-paint chrome hide and the first-visit spinner. */
public class WebViewEmbedChromeAssetTest {

    private static final String FRAGMENT =
            "app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt";

    @Test
    public void embedChromeIsSplicedIntoHtmlBeforeFirstPaint() throws IOException {
        String fragment = readRepositoryFile(FRAGMENT);

        assertTrue(fragment.contains("EMBED_ATTR = \"data-android-embed\""));
        assertTrue(fragment.contains("fun spliceEmbedChrome(html: String): String"));
        assertTrue(fragment.contains("spliceEmbedChrome("));

        int chrome = fragment.indexOf("EMBED_CHROME =");
        assertTrue(chrome >= 0);
        String block = fragment.substring(chrome, fragment.indexOf("</style>", chrome));
        assertTrue(block.contains("[data-android-embed=\"1\"] .sidebar"));
        assertTrue(block.contains("[data-android-embed=\"1\"] .mobile-header"));
        assertTrue(block.contains("--sidebar-width:0px"));
        assertTrue(block.contains("[data-android-embed=\"1\"] .bottom-tabs"));
        // The standalone HTML dashboard tags itself data-app-shell and must
        // keep its nav, so the pre-paint hide must never key on it.
        assertFalse(block.contains("data-app-shell"));
    }

    @Test
    public void splicedHtmlDropsTheUpstreamContentLength() throws IOException {
        String fragment = readRepositoryFile(FRAGMENT);

        assertTrue(fragment.contains("mime == \"text/html\" && connection.responseCode == 200"));
        assertTrue(fragment.contains("if (length > 0 && !splicedHtml)"));
    }

    @Test
    public void browserBuilderGuidanceCoversAppInitiatedRequestsAndTappedLinks() throws IOException {
        String fragment = readRepositoryFile(FRAGMENT);
        int intercept = fragment.indexOf("override fun shouldInterceptRequest(");
        int override = fragment.indexOf("override fun shouldOverrideUrlLoading(", intercept);
        assertTrue(intercept >= 0 && override > intercept);
        String directLoad = fragment.substring(intercept, override);
        assertTrue(directLoad.indexOf("browserBuilderMessage(request.url.path)") >= 0);
        assertTrue(directLoad.indexOf("browserBuilderMessage(request.url.path)") < directLoad.indexOf("openDaemon(url"));
        assertTrue(fragment.indexOf("browserBuilderMessage(request.url.path)", override) > override);
    }

    @Test
    public void restoringThePreviousBuilderDestinationOpensTheNativeLayoutPicker() throws Exception {
        String graph = readRepositoryFile("app/src/main/res/navigation/nav_graph.xml");
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        org.w3c.dom.NodeList fragments = factory.newDocumentBuilder().parse(
                new org.xml.sax.InputSource(new java.io.StringReader(graph))).getElementsByTagName("fragment");
        String android = "http://schemas.android.com/apk/res/android";
        for (int i = 0; i < fragments.getLength(); i++) {
            org.w3c.dom.Element screen = (org.w3c.dom.Element) fragments.item(i);
            if ("@+id/dashboardBuilderFragment".equals(screen.getAttributeNS(android, "id"))) {
                assertEquals("com.overdrive.app.customlayouts.ui.CustomLayoutsFragment",
                        screen.getAttributeNS(android, "name"));
                assertEquals(0, screen.getElementsByTagName("argument").getLength());
                return;
            }
        }
        throw new AssertionError("Missing restored dashboard builder destination");
    }

    @Test
    public void writeFetchBridgeIsInstalledBeforePageScripts() throws IOException {
        String fragment = readRepositoryFile(FRAGMENT);

        int bridge = fragment.indexOf("private const val FETCH_BRIDGE_JS");
        int chrome = fragment.indexOf("private const val EMBED_CHROME");
        assertTrue(bridge >= 0);
        assertTrue(chrome > bridge);

        String bridgeBlock = fragment.substring(bridge, chrome);
        assertTrue(bridgeBlock.contains("window.fetch = function(input, init)"));
        assertTrue(bridgeBlock.contains(
                "AndroidBridge.httpRequest(fullUrl, method, body, JSON.stringify(headers))"));
        assertTrue(fragment.substring(chrome).contains("FETCH_BRIDGE_JS +"));
        assertTrue(fragment.contains("view?.evaluateJavascript(FETCH_BRIDGE_JS, null)"));
        assertEquals(1, countOccurrences(
                fragment, "window.fetch = function(input, init)"));
    }

    @Test
    public void spinnerCoversEveryWebViewLoad() throws IOException {
        String fragment = readRepositoryFile(FRAGMENT);

        // No warm-path shortcut: a page that skips the overlay on a revisit
        // makes navigation look inconsistent against the pages that show it.
        assertFalse(fragment.contains("warmedPaths"));
        assertFalse(fragment.contains("currentLoadKey"));

        int chrome = fragment.indexOf("private fun applyLoadChrome()");
        assertTrue(chrome >= 0);
        String body = fragment.substring(chrome, fragment.indexOf('}', chrome));
        assertTrue(body.contains("showLoading()"));
        assertFalse(body.contains("hideLoading()"));

        int show = fragment.indexOf("private fun showLoading()");
        assertTrue(show >= 0);
        String showBody = fragment.substring(show, fragment.indexOf("private fun hideLoading()"));
        assertTrue(showBody.contains("View.VISIBLE"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
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

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
    }
}
