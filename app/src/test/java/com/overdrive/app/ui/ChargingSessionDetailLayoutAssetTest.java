package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the charging-session detail dashboard and its legacy WebView contract. */
public class ChargingSessionDetailLayoutAssetTest {

    @Test
    public void detailViewUsesTheReferenceDashboardStructure() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/charging.html");

        assertTrue(html.contains("class=\"ch-detail-title-row\""));
        assertTrue(html.contains("id=\"detailLivePill\""));
        assertTrue(html.contains(
                "class=\"ch-detail-panel ch-detail-overview app-surface-card\""));
        assertTrue(html.contains("id=\"detailSocFill\""));
        assertTrue(html.contains(
                "class=\"ch-detail-panel ch-detail-metrics app-surface-card\""));
        assertTrue(html.contains("class=\"ch-detail-chart-grid\""));
        assertTrue(html.contains("id=\"detailChartPeak\""));
        assertTrue(html.contains("id=\"detailTempHigh\""));
        assertTrue(html.contains("id=\"detailTempLow\""));
        assertTrue(html.contains("id=\"detailTempAvg\""));
        assertTrue(html.contains("charging.js?v="));
    }

    @Test
    public void detailViewIsResponsiveAndLegacyWebViewSafe() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/charging.html");

        assertTrue(html.contains("grid-template-columns: repeat(3, minmax(220px, 1fr))"));
        assertTrue(html.contains(".ch-detail-chart-grid { display: grid; grid-template-columns: 1fr 1fr"));
        assertTrue(html.contains("@media (max-width: 1180px)"));
        assertTrue(html.contains("@media (max-width: 720px)"));
        assertTrue(html.contains(".ch-detail-metrics { grid-template-columns: 1fr; }"));
        assertTrue(html.contains("display: -webkit-flex; display: flex"));
        assertFalse(ruleFor(html, ".ch-detail-title-row").contains("gap:"));
        assertFalse(ruleFor(html, ".ch-detail-location-line").contains("gap:"));
    }

    @Test
    public void detailControllerPopulatesOverviewAndChartSummaries() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/charging.js");

        assertTrue(script.contains("document.body.classList.add('charging-detail-open')"));
        assertTrue(script.contains("document.body.classList.remove('charging-detail-open')"));
        assertTrue(script.contains("this._setText('detailStartedAt'"));
        assertTrue(script.contains("this._setText('detailSocValue'"));
        assertTrue(script.contains("socFill.style.width = fillPct + '%'"));
        assertTrue(script.contains("_fillDetailSampleSummary: function (samples)"));
        assertTrue(script.contains("this._setText('detailTempHigh'"));
        assertTrue(script.contains("this._setText('detailTempLow'"));
        assertTrue(script.contains("this._setText('detailTempAvg'"));
        assertTrue(script.contains("_drawTempLegend: function"));
        assertTrue(script.contains("ctx.setLineDash([3, 4])"));
    }

    private static String ruleFor(String css, String selector) {
        int start = css.indexOf(selector);
        if (start < 0) return "";
        int end = css.indexOf('}', start);
        return end < 0 ? css.substring(start) : css.substring(start, end + 1);
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
