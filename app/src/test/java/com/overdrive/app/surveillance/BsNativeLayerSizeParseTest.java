package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Locks the fission-panel size parse against the EXACT {@code dumpsys display} lines seen on-car.
 *
 * <p>Regression: the cluster projection box rendered SQUARE because the size parser picked the
 * largest-area {@code W x H} pair on the DisplayInfo line, which is the rotation/overscan envelope
 * {@code largest app 1920 x 1920}, not the real {@code 1920 x 720} panel. The status endpoint then
 * reported panelH=1920 and the box aspect-locked to 1:1. These lines are copied verbatim from
 * {@code adb shell dumpsys display} on the target head unit.
 */
public class BsNativeLayerSizeParseTest {

    // Verbatim from the device (mOverrideDisplayInfo — the line that carries the bogus envelope).
    private static final String OVERRIDE_LINE =
        "mOverrideDisplayInfo=DisplayInfo{\"fission_bg_xdjaVirtualSurface, displayId 1\", "
        + "uniqueId \"virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0\", "
        + "app 1920 x 720, real 1920 x 720, overscan (80,50,80,50), largest app 1920 x 1920, "
        + "smallest app 720 x 720, mode 2, defaultMode 2, layerStack 1, type VIRTUAL, state ON}";

    private static final String BASE_LINE =
        "mBaseDisplayInfo=DisplayInfo{\"fission_bg_xdjaVirtualSurface, displayId 1\", "
        + "app 1920 x 720, real 1920 x 720, largest app 1920 x 720, smallest app 1920 x 720, "
        + "mode 2, layerStack 1, type VIRTUAL, state ON}";

    // The DisplayDeviceInfo line: a bare leading "1920 x 720" with no real/app labels.
    private static final String DEVICE_LINE =
        "DisplayDeviceInfo{\"fission_bg_xdjaVirtualSurface\": "
        + "uniqueId=\"virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0\", "
        + "1920 x 720, modeId 2, density 320, type VIRTUAL, state ON}";

    @Test
    public void overrideLinePicksRealNotEnvelope() {
        int[] p = BsNativeLayer.parseSizeFromDumpsysLine(OVERRIDE_LINE);
        assertNotNull(p);
        // MUST be the real panel, NOT the 1920x1920 "largest app" envelope.
        assertEquals(1920, p[0]);
        assertEquals(720, p[1]);
    }

    @Test
    public void baseLinePicksReal() {
        int[] p = BsNativeLayer.parseSizeFromDumpsysLine(BASE_LINE);
        assertNotNull(p);
        assertEquals(1920, p[0]);
        assertEquals(720, p[1]);
    }

    @Test
    public void deviceLineWithNoLabelsTakesLeadingPair() {
        int[] p = BsNativeLayer.parseSizeFromDumpsysLine(DEVICE_LINE);
        assertNotNull(p);
        assertEquals(1920, p[0]);
        assertEquals(720, p[1]);
    }

    @Test
    public void portraitClusterRealIsHonoured() {
        // A hypothetical portrait trim: real 720 x 1920 must be honoured (not flipped by envelope).
        String line = "mOverrideDisplayInfo=DisplayInfo{\"fission..., app 720 x 1920, "
            + "real 720 x 1920, largest app 1920 x 1920, smallest app 720 x 720, layerStack 1}";
        int[] p = BsNativeLayer.parseSizeFromDumpsysLine(line);
        assertNotNull(p);
        assertEquals(720, p[0]);
        assertEquals(1920, p[1]);
    }

    @Test
    public void diLink5MirrorPrefersPlainPhysicalCompositionStack() {
        String dump =
                "mBaseDisplayInfo=DisplayInfo{\"fission_bg_XDJAScreenProjection\", "
                + "displayId 2\", real 1920 x 720, layerStack 2, state ON}\n"
                + "mBaseDisplayInfo=DisplayInfo{\"shared_fission_bg_"
                + "XDJAScreenProjection_0\", displayId 3\", real 1920 x 720, "
                + "layerStack 3, state ON}\n"
                + "mBaseDisplayInfo=DisplayInfo{\"shared_fission_bg_"
                + "XDJAScreenProjection_1\", displayId 4\", real 1920 x 720, "
                + "layerStack 4, state ON}";

        BsNativeLayer.FissionDisplay display =
                BsNativeLayer.parseDiLink5MirrorDisplay(dump, 3);

        assertEquals(2, display.displayId);
        assertEquals(2, display.layerStack);
        assertEquals(1920, display.width);
        assertEquals(720, display.height);
    }

    @Test
    public void diLink5MirrorAcceptsDisplayIdInsideQuotedHeader() {
        String dump =
                "mBaseDisplayInfo=DisplayInfo{\"fission_bg_XDJAScreenProjection, "
                + "displayId 2\", real 1920 x 720, layerStack 2, state ON}\n"
                + "mBaseDisplayInfo=DisplayInfo{\"shared_fission_bg_"
                + "XDJAScreenProjection_0, displayId 3\", real 1 x 1, "
                + "layerStack 3, state ON}";

        BsNativeLayer.FissionDisplay display =
                BsNativeLayer.parseDiLink5MirrorDisplay(dump, 3);

        assertEquals(2, display.displayId);
        assertEquals(2, display.layerStack);
        assertEquals(1920, display.width);
        assertEquals(720, display.height);
    }

    @Test
    public void diLink5MirrorRefusesSharedShadowWithoutComposedSource() {
        String dump =
                "mBaseDisplayInfo=DisplayInfo{\"shared_fission_bg_"
                + "XDJAScreenProjection_0\", displayId 3\", real 1920 x 720, "
                + "layerStack 3, state ON}\n"
                + "mBaseDisplayInfo=DisplayInfo{\"shared_fission_bg_"
                + "XDJAScreenProjection_1\", displayId 4\", real 1920 x 720, "
                + "layerStack 4, state ON}";

        BsNativeLayer.FissionDisplay display =
                BsNativeLayer.parseDiLink5MirrorDisplay(dump, 3);

        assertEquals(-1, display.displayId);
        assertEquals(-1, display.layerStack);
    }

    @Test
    public void diLink5MirrorCanUseSelectedNonSharedProductionTarget() {
        String dump =
                "mBaseDisplayInfo=DisplayInfo{\"XDJAScreenProjection_0\", "
                + "displayId 3\", real 1920 x 720, layerStack 3, state ON}\n"
                + "mBaseDisplayInfo=DisplayInfo{\"XDJAScreenProjection_1\", "
                + "displayId 4\", real 1920 x 720, layerStack 4, state ON}";

        BsNativeLayer.FissionDisplay display =
                BsNativeLayer.parseDiLink5MirrorDisplay(dump, 3);

        assertEquals(3, display.displayId);
        assertEquals(3, display.layerStack);
    }

    @Test
    public void aaosMirrorUsesTheActiveExternalPortAndItsReportedStack() {
        String port = "mBaseDisplayInfo=DisplayInfo{\"HDMI 屏幕\", displayId 1, FLAG_PRESENTATION, "
                + "real 1920 x 720, state ON, type EXTERNAL, uniqueId \"local:3\", layerStack 7}";
        String builtIn = port.replace("displayId 1", "displayId 0").replace("EXTERNAL", "INTERNAL");
        BsNativeLayer.FissionDisplay target = BsNativeLayer.parseAaosHdmiMirrorDisplay(builtIn + "\n" + port, 1);
        assertEquals(1, target.displayId);
        assertEquals(7, target.layerStack);
        assertEquals(1920, target.width);
        assertEquals(720, target.height);
        assertEquals(-1, BsNativeLayer.parseAaosHdmiMirrorDisplay(port.replace("local:3", "local:5"), 1).displayId);
        assertEquals(-1, BsNativeLayer.parseAaosHdmiMirrorDisplay(port.replace("EXTERNAL", "INTERNAL"), 1).displayId);
        assertEquals(-1, BsNativeLayer.parseAaosHdmiMirrorDisplay(port, 0).displayId);
        assertEquals(-1, BsNativeLayer.parseAaosHdmiMirrorDisplay(port + "\n"
                + port.replace("mBase", "mOverride").replace("state ON", "state OFF"), 1).displayId);
    }

    @Test
    public void factoryMiniMapPreviewUsesOnlyTheSelectedSharedMap() {
        String mini = "mBaseDisplayInfo=DisplayInfo{\"shared_fission_bg_XDJAScreenProjection_1\", "
                + "displayId 4, real 1920 x 720, layerStack 5, state ON}";
        String override = mini.replace("mBase", "mOverride").replace("x 720", "x 660");
        String plain = mini.replace("shared_fission_bg_XDJAScreenProjection_1", "fission_bg_XDJAScreenProjection")
                .replace("displayId 4", "displayId 2");
        String dump = plain + "\n" + mini + "\n" + override;
        BsNativeLayer.FissionDisplay target = BsNativeLayer.parseDiLink5MirrorDisplay(dump, 4, true);
        assertEquals(5, target.layerStack);
        assertEquals(660, target.height);
        assertEquals(-1, BsNativeLayer.parseDiLink5MirrorDisplay(dump, 2, true).displayId);
        assertEquals(-1, BsNativeLayer.parseDiLink5MirrorDisplay(dump.replace("x 660", "x 1"), 4, true).displayId);
    }
}
