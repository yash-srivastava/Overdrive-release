package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
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

        int chrome = fragment.indexOf("EMBED_CHROME = ");
        assertTrue(chrome >= 0);
        String block = fragment.substring(chrome, fragment.indexOf("</style>", chrome));
        assertTrue(block.contains("[data-android-embed=\"1\"] .sidebar"));
        assertTrue(block.contains("[data-android-embed=\"1\"] .mobile-header"));
        assertTrue(block.contains("--sidebar-width:0px"));
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
    public void spinnerCoversOnlyPagesThisProcessHasNotPainted() throws IOException {
        String fragment = readRepositoryFile(FRAGMENT);

        assertTrue(fragment.contains("private val warmedPaths"));
        assertTrue(fragment.contains("warmedPaths.contains(it)"));
        assertTrue(fragment.contains("currentLoadKey?.let { warmedPaths.add(it) }"));
        assertTrue(fragment.contains("if (warm) hideLoading() else showLoading()"));

        int show = fragment.indexOf("private fun showLoading()");
        assertTrue(show >= 0);
        String body = fragment.substring(show, fragment.indexOf("private fun hideLoading()"));
        assertTrue(body.contains("View.VISIBLE"));
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
}
