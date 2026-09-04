package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the Vehicle screen's old-WebView spacing and model-fit behaviour. */
public class VehicleControlAssetTest {

    @Test
    public void tyreHeadersReuseSharedLegacyWebViewSpacing() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        assertEquals(4, count(html, "vc-tyre-head compact-status-pill"));
        assertEquals(4, count(html, "vc-tyre-dot compact-status-pill__dot"));
        assertFalse(ruleFor(css, ".vc-tyre-head").contains("\n    gap:"));
        assertTrue(ruleFor(css, ".vc-tyre-head")
                .contains("--compact-status-pill-dot-gap: 8px"));
        assertTrue(ruleFor(css, ".vc-tyre-psi-unit").contains("margin-left: 7px"));
    }

    /**
     * The corners are a strip in the bottom bar, not cards floating over the
     * render: fixed-width cards pinned to the viewport corners collide with
     * the floating chrome at head-unit aspect ratios.
     */
    @Test
    public void tyreCornersRenderAsAStripInTheControlBar() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        assertEquals(4, count(html, "class=\"vc-tyre-cell\""));
        assertFalse(html.contains("vc-tyre-callout"));
        assertFalse(css.contains(".vc-tyre-callout"));
        // The strip is a bar sibling of the stage, so the 3D-surround flag
        // on .vc-viewport must reach it as a sibling. Model load must not
        // hide it — tyre data does not wait on the GLB.
        assertTrue(css.contains(".vc-viewport[data-3d-on=\"true\"] ~ .vc-bar .vc-tyre-strip"));
        assertFalse(css.contains(".vc-viewport[data-model-loading=\"true\"] ~ .vc-bar .vc-tyre-strip"));
        assertTrue(ruleFor(css, ".vc-tyre-cell").contains("flex: 1"));
        // Chrome 58 ignores flex `gap`, so the gutters must be margins.
        assertTrue(css.contains(".vc-tyre-cell + .vc-tyre-cell { margin-left: 4px; }"));
        assertFalse(ruleFor(css, ".vc-tyre-strip").contains("gap:"));
    }

    /** Trunk actions use the current Lucide door glyphs. */
    @Test
    public void trunkActionsUseOfficialLucideDoorGlyphs() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");

        String doorOpen = "M10 4a2 2 0 0 1 2.36-1.968l5.41.992A1.5 1.5 0 0 1 19 4.5V21l-7.876.992A1 1 0 0 1 10 21z";
        String doorClosed = "M19 21V5a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2v16";
        assertTrue(html.contains(doorOpen));
        assertTrue(html.contains(doorClosed));
        // Tab and panel action share one revision of the glyph.
        assertEquals(2, count(html, doorOpen));
        assertFalse(html.contains("<polyline points=\"9 8 12 5 15 8\"/>"));
        assertFalse(html.contains("<polyline points=\"9 12 12 15 15 12\"/>"));
    }

    /**
     * Memory tiles sit in the same row as heat/cool. A titled block under a
     * divider made the Seats sheet read as two stacked panels.
     */
    @Test
    public void seatMemoryTilesSitInTheSameRowAsHeatAndCool() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        int seats = html.indexOf("id=\"panelSeats\"");
        int windows = html.indexOf("id=\"panelWindows\"");
        String panel = html.substring(seats, windows);
        assertTrue(panel.contains("id=\"btnSeatMemory1\""));
        assertTrue(panel.contains("id=\"btnSeatMemory2\""));
        assertTrue(panel.contains("class=\"vc-tile memory\""));
        assertFalse(panel.contains("vc-block--divider"));
        assertFalse(panel.contains("vc-seat-memory"));
        assertFalse(css.contains(".vc-hold-hint"));
        assertFalse(css.contains(".vc-seat-memory-actions"));
    }

    /**
     * Seats tab and memory tiles use Material {@code airline_seat_recline_extra},
     * not the Lucide armchair that reads as a couch at 20px.
     */
    @Test
    public void seatsTabUsesTheMaterialReclinedSeat() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String seat = "M5.35 5.64c-.9-.64-1.12-1.88-.49-2.79";
        assertTrue(html.contains(seat));
        assertEquals(3, count(html, seat));
        assertFalse(html.contains("M19 9V6a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2v3"));
    }

    @Test
    public void findCarUsesTheMaterialExploreGlyph() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        assertTrue(html.contains("M12 10.9c-.61 0-1.1.49-1.1 1.1s.49 1.1 1.1 1.1"));
        assertFalse(html.contains("M19.07 4.93A10 10 0 0 1 22 12"));
    }

    @Test
    public void seatClimateTilesUseMaterialLeftRightGlyphsAndLabels() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String en = readRepositoryFile("app/src/main/assets/web/i18n/en.json");

        assertTrue(html.contains("m801-458-66-44 13-21"));
        assertTrue(html.contains("m561-458-66-44 13-21"));
        assertTrue(html.contains(">Left heat</span>"));
        assertTrue(html.contains(">Left cool</span>"));
        assertTrue(html.contains(">Right heat</span>"));
        assertTrue(html.contains(">Right cool</span>"));
        assertTrue(en.contains("\"driver_heat\": \"Left heat\""));
        assertFalse(html.contains("D.Heat"));
        assertFalse(html.contains("P.Cool"));
    }

    @Test
    public void ambientSliderThumbUsesTheSelectedSwatchNotAGrayDot() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        String thumb = ruleFor(css, ".vc-ambient-slider::-webkit-slider-thumb");
        assertTrue(thumb.contains("background: var(--ambient-thumb, var(--vc-text-primary))"));
        assertTrue(script.contains("wrap.style.setProperty('--ambient-thumb'"));
        assertFalse(thumb.contains("background: var(--color, var(--primary))"));
        // A ring of the card background, not a white rim: the rim read as a
        // bullseye and left the swatch too small to judge against the ramp.
        assertTrue(thumb.contains("box-shadow: 0 0 0 3px var(--vc-tile-bg)"));
        assertFalse(thumb.contains("border: 2px solid #fff"));
        assertTrue("a bordered thumb without border-box overflows the 20px track",
                thumb.contains("box-sizing: border-box"));
    }

    /**
     * The showroom rig doubles as a specular source: the GLB paint is
     * low-roughness, so the car reads as a mirror once the rig runs hot.
     */
    @Test
    public void showroomLightingStaysBelowMirrorBrightness() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(script.contains("this.renderer.toneMappingExposure = 0.95;"));
        assertTrue(script.contains("new THREE.HemisphereLight(0x88aacc, 0x222244, 0.75)"));
        assertTrue(script.contains("new THREE.DirectionalLight(0xffffff, 0.8)"));
        assertTrue(script.contains("mat.envMapIntensity = 0.6;"));
        // Restore-on-bowl-exit must read the live intensities; a second copy of
        // the numbers here silently un-dims the rig.
        assertTrue(script.contains("{ light: keyLight,  base: keyLight.intensity }"));
        assertFalse(script.contains("{ light: keyLight,  base: 1.2 }"));
    }

    /**
     * A titled block with an optional caption is the one primitive every panel
     * that outgrows a row of tiles uses. It must not collide with the existing
     * `.vc-group` segmented-button primitive.
     */
    @Test
    public void panelsShareOneTitledBlockPrimitive() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        assertTrue(ruleFor(css, ".vc-block").contains("flex-direction: column"));
        assertTrue(ruleFor(css, ".vc-group").contains("display: inline-flex"));
        assertFalse("width cannot absorb the .vc-panel-row gutter",
                ruleFor(css, ".vc-block--full").contains("width: 100%"));

        // Climate: labelled steppers on their own row instead of bare siblings.
        assertTrue(html.contains("class=\"vc-block vc-block--stepper\""));
        assertTrue(html.contains("data-i18n=\"vehicle.temperature\""));
        assertTrue(html.contains("data-i18n=\"vehicle.fan\""));
        assertTrue(ruleFor(css, ".vc-stepper").contains("height: 48px"));
        assertTrue(ruleFor(css, ".vc-stepper > .vc-mini").contains("border: none"));

        // Charging reuses the same block, no bespoke charge-* wrappers left.
        assertFalse(css.contains(".vc-charge-block"));
        assertFalse(css.contains(".vc-charging-section"));
        assertFalse(css.contains(".vc-charge-footer"));
    }

    /** Tiles need 8px of air; 2px margins had them nearly touching. */
    @Test
    public void panelTilesAreNotShoulderToShoulder() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        assertTrue(css.contains(".vc-panel-row > * { margin: 4px; }"));
    }

    /**
     * A fixed badge cannot fit both a 58px tile and a 40px switch, and on a
     * connected account it marks every gated control for no reason. State only.
     */
    @Test
    public void cloudGatingIsStateNotAPerButtonBadge() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        assertFalse(css.contains("data-requires-cloud=\"true\"]::after"));
        assertFalse(css.contains("data-cloud-state=\"connected\"]::after"));
        assertFalse(css.contains("data-cloud-state=\"checking\"]::after"));
        assertFalse("the badge dodge padding goes with the badge",
                css.contains("padding-left: 26px; padding-right: 26px;"));

        String gated = ruleFor(css, ".vc-charge-switch[data-cloud-state=\"unavailable\"]");
        assertTrue(gated.contains("border-style: dashed"));
        assertTrue(gated.contains("var(--vc-text-muted)"));
    }

    /** One toast component for the whole app, anchored above the control dock. */
    @Test
    public void vehicleUsesTheSharedToastAboveTheDock() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertFalse(html.contains("id=\"vcToast\""));
        assertFalse(css.contains(".vc-toast"));
        assertTrue(html.contains("<div id=\"toastContainer\" class=\"toast-container\"></div>"));
        assertTrue(script.contains("BYD.core.toast(message, type || 'info', 2500)"));
        // Static inside the bottom bar's flex column, so it clears the dock in
        // every panel state without tracking the panel height.
        assertTrue(ruleFor(css, ".vc-bar > .toast-container").contains("position: static"));
    }

    /** No glow shadows or raw colour literals on the corners. */
    @Test
    public void tyreCellsUseSemanticStatusTokens() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        assertFalse(css.contains("rgba(0, 212, 170, 0.28)"));
        assertFalse(css.contains("rgba(0,212,170,0.82)"));
        assertTrue(css.contains(
                ".vc-tyre-cell[data-state=\"alert\"]   { background: var(--status-danger-container); }"));
        assertFalse(ruleFor(css, ".vc-tyre-dot").contains("box-shadow"));
    }

    /**
     * The cloud gate carries a CTA that the bridge turns into real navigation,
     * with a web route for tunnel sessions.
     */
    @Test
    public void cloudGateOffersNavigationRatherThanInstructions() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt");

        assertTrue(html.contains("id=\"cloudModalConnect\""));
        assertTrue(html.contains("data-i18n=\"vehicle.cloud_connect_cta\""));
        assertFalse("the gate must not tell the user to go hunting in Settings",
                html.contains("Go to Surveillance Settings"));
        assertTrue(script.contains("AndroidBridge.navigate('bydCloud');"));
        assertTrue(script.contains("window.location.href = 'byd-cloud.html';"));
        // The page names a destination; the allowlist decides what that means.
        assertTrue(fragment.contains("\"bydCloud\" -> R.id.bydCloudFragment"));
        assertTrue(fragment.contains("else -> return \"unknown_destination\""));
    }

    /**
     * Panels have to be readable at head-unit width. The Chrome 58 WebView
     * ignores flex `gap`, so every gutter in these layouts is a margin.
     */
    @Test
    public void categoryPanelsStackInsteadOfOverflowingOneRow() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        // Windows: a card per aperture with a full label, not an abbreviation.
        assertTrue(html.contains("data-i18n=\"vehicle.pos_front_left\""));
        assertTrue(ruleFor(css, ".vc-window-row").contains("width: calc(50% - 8px)"));
        assertFalse(ruleFor(css, ".vc-window-grid").contains("gap:"));
        assertFalse(ruleFor(css, ".vc-window-presets").contains("gap:"));
        // Lights: ambient ramp on its own full-width row under the DRL tile.
        assertTrue(html.contains("class=\"vc-panel-row vc-stack-grid\" id=\"panelLights\""));
        assertTrue(ruleFor(css, ".ambient-wrapper").contains("width: 100%"));
        assertTrue(ruleFor(css, ".vc-ambient-slider").contains("width: 100%"));
        // Charging: labelled rows, compact footer actions — not full-width save bricks.
        assertTrue(html.contains("data-i18n=\"vehicle_control.window\""));
        assertTrue(html.contains("data-i18n=\"vehicle_control.repeat\""));
        assertTrue(html.contains("class=\"vc-schedule-bar vc-block-actions\""));
        assertTrue(html.contains("data-i18n=\"vehicle_control.start_charge\""));
        assertTrue(html.contains("data-i18n=\"vehicle_control.section_ac_current\""));
        assertFalse(ruleFor(css, ".vc-charge-save").contains("width: 100%"));
        // Start now / Save are an equal-width pair, not a full-width slab.
        assertTrue(ruleFor(css, ".vc-block-actions > .vc-charge-save").contains("max-width: 160px"));
        // The charge-limit slider must stay full width at every breakpoint.
        assertFalse(css.contains(".vc-soc-slider { width: 120px; }"));
        assertFalse(ruleFor(css, ".vc-schedule-bar").contains("gap:"));
        assertFalse(ruleFor(css, ".vc-schedule-grid").contains("gap:"));
        assertTrue(css.contains(".vc-schedule-bar + .vc-schedule-bar { margin-top: 8px; }"));

        // Precondition is the same labelled stepper row as Temperature / Fan,
        // not a left-clustered schedule bar with Save parked on the far edge.
        int climate = html.indexOf("id=\"climateScheduleSection\"");
        int sound = html.indexOf("id=\"panelSound\"");
        String pre = html.substring(climate, sound);
        assertTrue(pre.contains("class=\"vc-block-row\""));
        assertTrue(pre.contains("data-i18n=\"vehicle.precondition_time\""));
        assertTrue(pre.contains("class=\"vc-climate-actions\""));
        assertFalse(pre.contains("vc-schedule-bar"));
        assertTrue(ruleFor(css, ".vc-climate-actions").contains("height: 48px"));
    }

    /** The dock clips the sheet, so the panel carries no rounded lid of its own. */
    @Test
    public void dockClipsTheSheetInsteadOfASecondRoundedLid() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        assertTrue(html.contains("class=\"vc-dock\" id=\"vcDock\""));
        assertTrue(ruleFor(css, ".vc-dock").contains("overflow: hidden"));
        assertTrue(ruleFor(css, ".vc-panel").contains("border: none"));
        assertFalse(ruleFor(css, ".vc-panel").contains("border-radius: var(--radius-md)"));
    }

    @Test
    public void viewModeChipKeepsALabelWithoutWaitingForI18n() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(html.contains("class=\"vc-view-to-3d\" data-i18n=\"vehicle.view_mode_3d\""));
        assertTrue(html.contains("class=\"vc-view-to-lite\" data-i18n=\"vehicle.view_mode_lite\""));
        assertFalse(script.contains("label.textContent = this.liteMode"));
        assertTrue(script.contains("if (this._viewModeSwitching) return;"));
    }

    @Test
    public void viewModeSwitchRebuildsTheStageWithoutReloadingThePage() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertFalse(script.contains("location.reload();"));
        assertTrue(script.contains("switchViewMode: function()"));
        assertTrue(script.contains("_startImmersiveScene: function()"));
        assertTrue(script.contains("_teardownImmersiveScene: function()"));

        // The toggle loads the vendor stack from the page's own list rather
        // than a second copy of the URLs.
        assertTrue(html.contains("window.VC_VENDOR_CHAIN = ["));
        assertTrue(script.contains("var chain = window.VC_VENDOR_CHAIN || [];"));
        assertFalse(script.contains("vendor/three.min.js"));

        // Teardown has to end the render loop and release the GL context,
        // otherwise lite keeps a disposed scene animating.
        assertTrue(script.contains("this._renderLoopActive = false;"));
        assertTrue(script.contains("this.renderer.dispose();"));
        assertTrue(script.contains("window.removeEventListener('resize', this._onWindowResize)"));

        // Re-entrant init paths must not stack listeners or duplicate nodes.
        assertTrue(script.contains("if (sel._vcBound) {"));
        assertTrue(script.contains("if (!toggle || toggle._vcBound) return;"));
        assertTrue(script.contains("container.innerHTML = '';"));
    }

    /**
     * Lite mode paints the same VehicleArt render the Dashboard uses, including
     * vehicle_fallback for an unset id.
     */
    @Test
    public void liteHeroUsesDashboardFallbackArtRatherThanAnIcon() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/ModelsApiHandler.java");

        assertTrue(html.contains("id=\"vcLiteHeroImg\""));
        assertFalse(html.contains("M19 17h2c.6 0 1-.4 1-1v-3"));
        assertFalse(css.contains(".vc-lite-hero svg"));
        assertTrue(script.contains("apply('');"));
        assertTrue(script.contains("VC.artIdFromSelected(selected)"));
        assertTrue(script.contains("if (selected.modelSource === 'unset') return '';"));
        assertFalse(script.contains("apply((selected && selected.modelId) || '');"));
        assertTrue(script.contains("fetch('/api/models/art?id='"));
        assertTrue(api.contains("VehicleArt.INSTANCE.drawableFor(id)"));

        // Under uid 2000 the shared context resolves com.android.shell, so its
        // Resources cannot open our raw drawables. Art comes from an
        // AssetManager built on our own APK.
        assertTrue(api.contains("res.openRawResource(drawableId)"));
        assertFalse(api.contains("ctx.getResources().openRawResource(drawableId)"));
        assertTrue(api.contains("CameraDaemon.getApkAssets()"));
        assertTrue(api.contains("new android.content.res.Resources(assets, metrics, config)"));
        assertTrue(readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java")
                .contains("public static android.content.res.AssetManager getApkAssets()"));
        assertTrue(readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/vehicle/VehicleArt.kt")
                .contains("else -> R.drawable.vehicle_fallback"));
    }

    @Test
    public void categoryPanelsDoNotShowACloudAccountBanner() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertFalse(html.contains("id=\"vcCloudHint\""));
        assertFalse(script.contains("updateCloudHint"));
    }

    @Test
    public void loadedModelSitsOnTheGroundGrid() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(script.contains("GROUND_Y: -0.01,"));
        assertTrue(script.contains("gridHelper.position.y = this.GROUND_Y;"));
        assertTrue(script.contains("this.carModel.position.y += this.GROUND_Y - box.min.y;"));
        assertFalse(script.contains("this.carModel.position.y += 0.1;"));
    }

    @Test
    public void modelFitIsGenericRatherThanVehicleBranched() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        String manifest = readRepositoryFile(
                "app/src/main/assets/web/shared/models/manifest.json");

        assertFalse(manifest.contains("\"displayScale\""));
        assertTrue(script.contains("entry.displayScale"));
        assertTrue(script.contains("_fitLoadedCarModel"));
        assertFalse(script.contains("activeModelId === 'atto3'"));
        assertFalse(script.contains("activeModelId == 'atto3'"));
    }

    @Test
    public void rawLimitsStillCatchLowPressureWhenSdkSaysNormal() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(script.contains("typeof corner.pressureState === 'number'"));
        assertTrue(script.contains("&& corner.pressureState >= 1) return 'warn'"));
        // The numeric net still runs after the SDK enum says normal, but the
        // limits are now the user's configured per-axle kPa band rather than
        // hardcoded PSI literals.
        assertTrue(script.contains("if (corner.kPa <= lim.criticalLow) return 'alert'"));
        assertTrue(script.contains("if (corner.kPa < low || corner.kPa > high) return 'warn'"));
        assertFalse(script.contains(
                "corner.pressureState >= 1) return 'warn';\n            return 'normal'"));
        // No PSI-literal thresholds may come back: PSI is a rounded display
        // unit, so comparing in it lets the corner colour disagree with the
        // notification thresholds.
        assertFalse(script.contains("corner.psi < 34"));
        assertFalse(script.contains("corner.psi < 28"));
        assertFalse(script.contains("corner.psi < 22"));
        assertFalse(script.contains("corner.psi > 50"));
    }

    @Test
    public void tyrePressureLabelsUseConnectedSdkEncoding() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(script.contains(
                "corner.pressureState === 1) return BYD.i18n.t('vehicle.tyre_high')"));
        assertTrue(script.contains(
                "corner.pressureState === 2) return BYD.i18n.t('vehicle.tyre_low')"));
    }

    /** The per-axle band must come from the server, not be re-hardcoded. */
    @Test
    public void tyreLimitsComeFromServerConfig() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(script.contains("_tyreLimits"));
        assertTrue(script.contains("if (tyres.limits)"));
        // Front and rear are independent, so the token/label helpers must be
        // told which axle they are judging.
        assertTrue(script.contains("_tyreStateToken: function(corner, isFront)"));
        assertTrue(script.contains("_tyreStateLabel: function(corner, isFront)"));
        assertTrue(script.contains("var isFront = i < 2;"));
    }

    /**
     * The kPa net must be evaluated BEFORE the firmware enum, so the worst of the
     * two wins as it does server-side (BydDataCollector: level = max(enum, kPa)).
     * An enum-first early return painted a deflated tyre orange while the server
     * raised a CRITICAL alert for it — the deflation case normally trips the
     * firmware flag too, so that was the common path, not an edge case.
     */
    @Test
    public void tyreCriticalOutranksFirmwareWarnInCornerColour() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        int criticalCheck = script.indexOf("if (corner.kPa <= lim.criticalLow) return 'alert'");
        int enumWarn = script.indexOf("&& corner.pressureState >= 1) return 'warn'");
        assertTrue("both branches must exist", criticalCheck > 0 && enumWarn > 0);
        assertTrue("kPa criticalLow check must precede the enum warn branch",
                criticalCheck < enumWarn);
        // The caption's low test shares the token's boundary (criticalLow may
        // equal an axle low), so a red corner is never labelled OK.
        assertTrue(script.contains("if (corner.kPa < low || corner.kPa <= lim.criticalLow)"));
    }

    @Test
    public void inCarRendererResizesAndUsesBoundedGpuWork() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(script.contains("_watchCanvasSize"));
        assertTrue(script.contains("new ResizeObserver(sync)"));
        assertTrue(script.contains("this.renderer.setPixelRatio(window.AndroidBridge"));
        assertTrue(script.contains("type: typeof WebAssembly === 'object' ? 'wasm' : 'js'"));
        assertTrue(script.contains("now - this._lastRenderFrame < 32"));
    }

    @Test
    public void acChargingCurrentLimitStaysVisibleAndUsesFiveStateReadback() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(html.contains("id=\"acChargeCurrentSection\""));
        assertFalse(html.contains("id=\"acChargeCurrentSection\" style=\"display:none;\""));
        assertEquals(5, count(html, "class=\"vc-seg\" data-state="));
        assertEquals(5, count(html, "data-state=\"1\" disabled")
                + count(html, "data-state=\"2\" disabled")
                + count(html, "data-state=\"3\" disabled")
                + count(html, "data-state=\"4\" disabled")
                + count(html, "data-state=\"5\" disabled"));
        assertTrue(html.contains("vehicle-control.js?v=vclite17"));
        assertTrue(script.contains("fetch('/api/vehicle/ac-charge-current-limit')"));
        assertTrue(script.contains("self.apiPost('/api/vehicle/ac-charge-current-limit'"));
        assertTrue(script.contains("startAcChargeCurrentSync: function()"));
        assertTrue(script.contains("}, 15 * 1000);"));
        assertTrue(script.contains("if (panelId === 'panelCharging')"));
        assertTrue(script.contains("this.fetchAcChargeCurrentLimit();"));
        assertFalse(script.contains(
                "state.checked && state.supported !== false ? '' : 'none'"));
        assertTrue(script.contains("state.available !== true"));
        assertTrue(script.contains(
                "self.vehicleState.acChargeCurrentLimit.available = false;"));
    }

    private static int count(String text, String needle) {
        int result = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            result++;
            from += needle.length();
        }
        return result;
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
