package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Pins the Seat Positions page to the shared dashboard chrome. */
public class SeatPositionsAssetTest {

    @Test
    public void railDestinationDoesNotShowAnUpArrow() throws IOException {
        String activity = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        int setStart = activity.indexOf("appBarConfiguration = AppBarConfiguration(");
        assertTrue(setStart >= 0);
        String set = activity.substring(setStart, activity.indexOf(')', setStart));
        assertTrue(set.contains("R.id.seatPositionsFragment"));
        assertTrue(set.contains("R.id.vehicleControlFragment"));
        assertTrue(set.contains("R.id.liveViewFragment"));
    }

    @Test
    public void pageMatchesDashboardSurfacesInsteadOfBannerCards() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/seat-positions.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.css");
        String script = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.js");

        assertTrue(html.contains("seat-positions.css?v=2"));
        assertTrue(html.contains("seat-positions.js?v=2"));
        assertTrue(html.contains("class=\"modal-card sp-dialog\""));
        assertTrue(html.contains("class=\"btn sp-dialog-cancel\""));

        assertFalse(html.contains("info-box-note"));
        assertFalse(html.contains("rgba(255,179,0"));
        assertFalse(css.contains("seat-side.png"));
        assertFalse(css.contains("border: 1px dashed"));
        assertFalse(css.contains("border-radius: var(--radius-full)"));
        assertFalse(css.contains("box-shadow: var(--shadow-lg)"));

        assertTrue(script.contains("class=\"seat-svg\""));
        assertTrue(script.contains("emptyHtml()"));
        assertTrue(script.contains("!this.positioningBlocked"));
    }

    /**
     * The gate speaks once: a single status line carrying the highest-priority
     * condition, with no stacked banner cards or header state pill.
     */
    @Test
    public void gateStateIsASingleLineRatherThanStackedBanners() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/seat-positions.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.js");

        assertTrue(html.contains("id=\"spStatus\""));
        assertTrue(html.contains("id=\"spStatusText\""));
        assertFalse(html.contains("id=\"spGate\""));
        assertFalse(html.contains("id=\"spMoving\""));
        assertFalse(html.contains("id=\"spUnconfirmed\""));
        assertFalse(html.contains("id=\"spStateBadge\""));

        assertTrue(script.contains("gateState()"));
        assertTrue(script.contains("host.setAttribute('data-state', gate.state)"));
        // Priority order: unpowered motors, then gear, then unconfirmed axis map.
        int acc = script.indexOf("seatpos.gate_acc_off");
        int moving = script.indexOf("seatpos.gate_moving");
        int unconfirmed = script.indexOf("seatpos.unconfirmed_note");
        assertTrue(acc >= 0 && acc < moving);
        assertTrue(moving < unconfirmed);
    }

    /**
     * With no readable geometry there is no pose to draw, so the art holder is
     * left empty and collapses instead of reserving hero height for nothing.
     */
    @Test
    public void currentPositionCollapsesWhenTheSeatCannotBeRead() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/seat-positions.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.css");
        String script = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.js");

        assertTrue(html.contains("class=\"card-body sp-now-body\""));
        assertTrue(css.contains(".sp-now-art:empty { display: none; }"));
        assertTrue(script.contains("glyphEl.innerHTML = this.current ? this.glyph(this.current) : '';"));
        assertFalse(css.contains(".sp-metric"));
        assertFalse(css.contains(".sp-hero-metrics"));
    }

    @Test
    public void chrome58SpacingUsesMarginsNotFlexGap() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.css");
        assertFalse(ruleFor(css, ".sp-now-body").contains("gap:"));
        assertFalse(ruleFor(css, ".sp-status").contains("gap:"));
        assertFalse(ruleFor(css, ".sp-axis-line").contains("gap:"));
        assertTrue(ruleFor(css, ".sp-now-art").contains("margin-right: 16px"));
        assertTrue(ruleFor(css, ".sp-status-dot").contains("margin-right: 10px"));
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
