package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the charging Stats dashboard hierarchy and real-data metric contract. */
public class ChargingStatsLayoutAssetTest {

    @Test
    public void statsTabUsesTheReferenceDashboardHierarchy() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/charging.html");

        assertTrue(html.contains("class=\"charging-stats-intro\""));
        assertTrue(html.contains("id=\"statsPeriodTabs\""));
        assertTrue(html.contains("class=\"hero-row\" data-tab=\"stats\""));
        assertTrue(html.contains(
                "class=\"ch-card stats-soc-card app-surface-card\""));
        assertTrue(html.contains("class=\"stats-lower-grid\""));
        assertTrue(html.contains("id=\"efficiencyCard\""));
        assertTrue(html.contains("id=\"lifetimeCard\""));
        assertTrue(html.contains("class=\"stats-lifetime-row\""));
        assertTrue(html.contains("id=\"chargingPageTitle\""));
        assertTrue(html.contains("id=\"chargingMobileTitle\""));
        assertTrue(html.contains("class=\"dashboard-soc-gauge\""));
        assertTrue(html.contains("id=\"socRangeValue\""));
        assertTrue(html.contains("id=\"socSohValue\""));
        assertTrue(html.contains(
                "id=\"socCircleCanvas\" width=\"112\" height=\"112\" data-css-size=\"112\""));
        assertTrue(html.contains(".ch-hero-card--soc {"));
        assertTrue(html.contains("min-height: 184px;"));
        assertTrue(html.contains("width: 76px;"));
        assertTrue(html.contains("font-size: 24px;"));
        assertTrue(html.contains("font-size: 7px;"));
        assertTrue(html.contains(".dashboard-soc-meta {"));
        assertTrue(html.contains("margin-top: 14px;"));
    }

    @Test
    public void statsPowerUsesMeasuredOrRecordedSessionData() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/charging.js");

        assertTrue(script.contains("currentDays: 7"));
        assertTrue(script.contains("_periodAveragePower: function (live)"));
        assertTrue(script.contains("live.isEstimated !== true"));
        assertTrue(script.contains("totalEnergy / totalHours"));
        assertTrue(script.contains("pick('--brand-primary'"));
        assertTrue(script.contains(".charge-period-control .filter-tab[data-days]"));
        assertTrue(script.contains("canvas.clientWidth > 900 ? 260 : 200"));
        assertTrue(script.contains("_syncTabPresentation: function (activeId)"));
        assertTrue(script.contains("self._syncTabPresentation(id)"));
        assertTrue(script.contains("this._dist(live.rangeKm)"));
        assertTrue(script.contains("'SOH ' + Math.round(live.sohPercent) + '%'"));
        assertTrue(script.contains("canvas.getAttribute('data-css-size')"));
        assertTrue(script.contains("var size = logicalSize || canvas.clientWidth"));
        assertFalse(script.toLowerCase().contains("carbon saved"));
    }

    @Test
    public void statsVehicleGaugeUsesLiveRangeAndCanonicalSoh() throws IOException {
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/ChargingApiHandler.java");

        assertTrue(handler.contains("vm.getDrivingRange()"));
        assertTrue(handler.contains("soh.getDisplaySoh()"));
        assertTrue(handler.contains("live.put(\"rangeKm\""));
        assertTrue(handler.contains("live.put(\"sohPercent\""));
        assertFalse(handler.contains("rangeKm / socPct"));
    }

    @Test
    public void statsFlexLayoutsRetainLegacyWebViewSpacing() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/charging.html");

        assertTrue(html.contains(".charging-stats-intro > * + *"));
        assertTrue(html.contains(".ch-hero-card > * + *"));
        assertTrue(html.contains(".stats-lifetime-row > * + *"));
        assertFalse(ruleFor(html, ".charging-stats-intro").contains("gap:"));
        assertFalse(ruleFor(html, ".ch-hero-card").contains("gap:"));
        assertFalse(ruleFor(html, ".stats-lifetime-row").contains("gap:"));
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
