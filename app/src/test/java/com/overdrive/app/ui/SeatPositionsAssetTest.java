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

        assertTrue(html.contains("seat-positions.css?v=5"));
        assertTrue(html.contains("seat-positions.js?v=5"));
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

    /**
     * Axis readouts are unboxed label/value pairs. Bordered nowrap chips plus a
     * fixed-width group column made every row ragged once a label ran long.
     */
    @Test
    public void axisReadoutsAreLabelValuePairsRatherThanChips() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.css");

        String axis = ruleFor(css, ".sp-axis {");
        assertFalse(axis.contains("border:"));
        assertFalse(axis.contains("background:"));
        assertFalse(axis.contains("white-space: nowrap"));
        assertTrue(axis.contains("margin: 2px 14px 2px 0"));

        String group = ruleFor(css, ".sp-axis-group {");
        assertFalse("a fixed gutter cannot hold \"Left mirror sideways\"",
                group.contains("width: 60px"));
        assertTrue(group.contains("margin: 2px 10px 2px 0"));

        assertTrue(ruleFor(css, ".sp-axis .v").contains("tabular-nums"));
    }

    /**
     * Thirteen values per position is a wall of numbers on a page whose rows are picked
     * by name, so the readout collapses behind a disclosure that survives the poll.
     */
    @Test
    public void axisValuesSitBehindADisclosure() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.css");
        String script = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.js");

        assertTrue(css.contains(".sp-details:not(.is-open) .sp-axes { display: none; }"));
        assertTrue(script.contains("data-act=\"details\""));
        assertTrue(script.contains("seatpos.show_details"));
        assertTrue(script.contains("seatpos.hide_details"));

        // Keyed state, or the 5s geometry poll would collapse an open card.
        assertTrue(script.contains("detailsOpen: {}"));
        assertTrue(script.contains("this.axesHtml(this.current, 'current')"));
        assertTrue(script.contains("this.axesHtml(p.axes, p.id)"));

        String toggle = ruleFor(css, ".sp-details-toggle {");
        assertFalse(toggle.contains("border-radius: var(--radius-full)"));
        assertTrue(toggle.contains("border-radius: var(--radius-sm)"));
    }

    /** A flex parent collapses the whitespace, so the swatch gutter has to be a margin. */
    @Test
    public void ambientSwatchIsNotFlushAgainstItsLabel() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.css");
        assertTrue(ruleFor(css, ".sp-parts-summary").contains("display: flex"));
        assertTrue(ruleFor(css, ".sp-swatch {").contains("margin-left: 7px"));
    }

    /**
     * The seat art is Material's {@code airline_seat_recline_extra}, declared once
     * and shared by the hero, every row and the empty state.
     */
    @Test
    public void seatArtIsTheMaterialReclinedSeat() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.css");
        String script = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.js");

        assertTrue(script.contains("SEAT_PATH: 'M5.35 5.64c-.9-.64-1.12-1.88-.49-2.79"));
        assertTrue(script.contains("viewBox=\"0 0 24 24\""));
        // One declaration, shared by the glyph and the empty-state tile.
        assertTrue(script.contains("<path d=\"' + this.SEAT_PATH + '\"/>"));
        assertFalse("the armchair is gone from the hero, the rows and the empty state",
                script.contains("M19 9V6a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2v3"));

        // The pose was inferred, never measured; the numbers behind Show details say it exactly.
        assertFalse(script.contains("backDeg"));
        assertFalse(script.contains("rotate("));
        assertFalse(css.contains(".seat-svg-part"));
        assertFalse(css.contains(".seat-svg-floor"));

        // A square icon needs a square box.
        assertTrue(ruleFor(css, ".sp-now-art").contains("height: 64px"));
        assertTrue(ruleFor(css, ".sp-row-art").contains("height: 44px"));
    }

    /**
     * Art and buttons are top-aligned, so expanding a row's details grows it
     * downwards instead of re-centring everything in it.
     */
    @Test
    public void expandingDetailsDoesNotSlideTheArtOrTheButtons() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.css");
        assertTrue(ruleFor(css, ".sp-now-body").contains("align-items: flex-start"));
        assertTrue(ruleFor(css, ".sp-row {").contains("align-items: flex-start"));
    }

    /**
     * Readiness is the hero card's footer. As a standalone bar above the page it
     * read as an alert about nothing on the happy path.
     */
    @Test
    public void gateStatusIsTheHeroCardFooter() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/seat-positions.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/seat-positions.css");

        int card = html.indexOf("class=\"card sp-now\"");
        int status = html.indexOf("id=\"spStatus\"");
        int list = html.indexOf("id=\"spList\"");
        assertTrue(card >= 0 && status > card && status < list);

        String rule = ruleFor(css, ".sp-status {");
        assertTrue(rule.contains("border-top: 1px solid var(--outline-variant)"));
        assertFalse(rule.contains("border-radius"));
        assertFalse(rule.contains("margin-bottom"));
        assertFalse(rule.contains("background:"));
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
