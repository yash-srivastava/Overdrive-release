package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONArray;
import org.json.JSONObject;
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

    @Test
    public void tyreCardsHaveReadableDashboardSizingAndHealthyState() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        assertTrue(ruleFor(css, ".vc-tyre-callout").contains("width: 168px"));
        assertTrue(ruleFor(css, ".vc-tyre-psi-val").contains("font-size: 28px"));
        assertTrue(css.contains("rgba(0, 212, 170, 0.28)"));
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
    public void releasedAtto2AndSealion7UseBodyPaintMeshHints() throws Exception {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        JSONObject manifest = new JSONObject(readRepositoryFile(
                "app/src/main/assets/web/shared/models/manifest.json"));

        assertTrue(script.contains("modelEntry.paintMeshHint"));
        assertTrue(script.contains("paintName.indexOf(paintMeshHint) >= 0"));

        JSONObject atto2 = null;
        JSONObject sealion7 = null;
        JSONArray models = manifest.getJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            if ("atto2".equals(model.getString("id"))) {
                atto2 = model;
            } else if ("sealion7".equals(model.getString("id"))) {
                sealion7 = model;
            }
        }

        assertTrue(atto2 != null);
        assertEquals("mk_body", atto2.getString("paintMeshHint"));

        assertTrue(sealion7 != null);
        assertEquals("bodypaint", sealion7.getString("paintMeshHint"));
        assertEquals("sealion7.glb", sealion7.getString("file"));
        assertEquals(1645180, sealion7.getInt("sizeBytes"));
        assertEquals(
                "b1bd4c94aaf50e09ee5a294381bcec52b5160fe2cc3394e4b1c9d5ce37b055e2",
                sealion7.getString("sha256"));
        assertFalse(sealion7.getBoolean("bundled"));
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
        assertTrue(html.contains("vehicle-control.js?v=vclite8"));
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
