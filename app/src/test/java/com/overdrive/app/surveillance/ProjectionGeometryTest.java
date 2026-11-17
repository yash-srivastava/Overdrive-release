package com.overdrive.app.surveillance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProjectionGeometryTest {

    @Test
    public void fitLetterboxesWidePanelInSixteenByNineBox() {
        ProjectionGeometry.Mapping m = ProjectionGeometry.calculate(
                1920, 720, 10, 20, 1280, 720, ProjectionGeometry.SCALE_FIT);

        assertEquals(0, m.srcX);
        assertEquals(0, m.srcY);
        assertEquals(1920, m.srcW);
        assertEquals(720, m.srcH);
        assertEquals(10, m.dstX);
        assertEquals(140, m.dstY);
        assertEquals(1280, m.dstW);
        assertEquals(480, m.dstH);
    }

    @Test
    public void fitCentersWidePanelInPortraitBox() {
        ProjectionGeometry.Mapping m = ProjectionGeometry.calculate(
                1920, 720, 0, 0, 720, 1280, ProjectionGeometry.SCALE_FIT);

        assertEquals(0, m.dstX);
        assertEquals(505, m.dstY);
        assertEquals(720, m.dstW);
        assertEquals(270, m.dstH);
    }

    @Test
    public void fitUsesDeterministicTruncationAndCentersOddRemainder() {
        ProjectionGeometry.Mapping m = ProjectionGeometry.calculate(
                2, 1, 10, 20, 3, 3, ProjectionGeometry.SCALE_FIT);

        assertEquals(10, m.dstX);
        assertEquals(21, m.dstY);
        assertEquals(3, m.dstW);
        assertEquals(1, m.dstH);
    }

    @Test
    public void fillUsesTheWholeSourceAndDestination() {
        ProjectionGeometry.Mapping m = ProjectionGeometry.calculate(
                1920, 720, 7, 11, 301, 509, ProjectionGeometry.SCALE_FILL);

        assertEquals(0, m.srcX);
        assertEquals(0, m.srcY);
        assertEquals(1920, m.srcW);
        assertEquals(720, m.srcH);
        assertEquals(7, m.dstX);
        assertEquals(11, m.dstY);
        assertEquals(301, m.dstW);
        assertEquals(509, m.dstH);
    }

    @Test
    public void zoomCenterCropsSourceToBoxAspect() {
        ProjectionGeometry.Mapping m = ProjectionGeometry.calculate(
                1920, 720, 0, 0, 1280, 720, ProjectionGeometry.SCALE_ZOOM);

        assertEquals(320, m.srcX);
        assertEquals(0, m.srcY);
        assertEquals(1280, m.srcW);
        assertEquals(720, m.srcH);
        assertEquals(1280, m.dstW);
        assertEquals(720, m.dstH);
    }

    @Test
    public void zoomCenterCropsHeightForAnExtraWideBox() {
        ProjectionGeometry.Mapping m = ProjectionGeometry.calculate(
                1000, 1000, 3, 5, 1000, 333, ProjectionGeometry.SCALE_ZOOM);

        assertEquals(0, m.srcX);
        assertEquals(333, m.srcY);
        assertEquals(1000, m.srcW);
        assertEquals(333, m.srcH);
        assertEquals(3, m.dstX);
        assertEquals(5, m.dstY);
    }

    @Test
    public void projectionNeverProducesZeroSizedRectangles() {
        ProjectionGeometry.Mapping m = ProjectionGeometry.calculate(
                1920, 720, 0, 0, 1, 1, ProjectionGeometry.SCALE_FIT);

        assertEquals(1, m.dstW);
        assertEquals(1, m.dstH);
        assertTrue(m.dstX >= 0);
        assertTrue(m.dstY >= 0);
    }

    @Test
    public void inverseMappingUsesTheExactDestinationOffsetAndScale() {
        assertEquals(960.0,
                ProjectionGeometry.destinationToSource(650.0, 0, 1920, 10, 1280),
                0.0001);
    }

    @Test
    public void inverseMappingPreservesDestinationPixelEndpoints() {
        assertEquals(320.0,
                ProjectionGeometry.destinationToSource(10.0, 320, 1280, 10, 1280),
                0.0001);
        assertEquals(1599.0,
                ProjectionGeometry.destinationToSource(1289.0, 320, 1280, 10, 1280),
                0.0001);
    }

    @Test
    public void hostedInputRemovesPanelOffsetAndAppliesInverseBufferScale() {
        assertEquals(0.0,
                ClusterInputRelay.mapPanelToInput(480f, 480, 960, 1920), 0.0001);
        assertEquals(960.0,
                ClusterInputRelay.mapPanelToInput(960f, 480, 960, 1920), 0.0001);
        assertEquals(1918.0,
                ClusterInputRelay.mapPanelToInput(1439f, 480, 960, 1920), 0.0001);
        assertArrayEquals(
                new int[] { 960, 540 },
                ClusterInputRelay.mapPanelPointToInput(
                        960f, 450f, 480, 180, 960, 540, 1920, 1080));
    }

    @Test
    public void inverseMappingRejectsNonFiniteAndDegenerateInputs() {
        assertEquals(17.0,
                ProjectionGeometry.destinationToSource(
                        Double.POSITIVE_INFINITY, 17, 100, 0, 100),
                0.0);
        assertEquals(17.0,
                ProjectionGeometry.destinationToSource(42.0, 17, 100, 0, 0),
                0.0);
    }

    @Test
    public void everyModeStaysCenteredAndBoundedAcrossLandscapePortraitAndSquareRatios() {
        int[][] sizes = {
                { 1, 1 }, { 2, 1 }, { 1, 2 }, { 3, 2 }, { 2, 3 },
                { 16, 9 }, { 9, 16 }, { 1920, 720 }, { 720, 1920 }, { 1279, 719 }
        };

        for (int[] source : sizes) {
            for (int[] box : sizes) {
                int sw = source[0], sh = source[1], bw = box[0], bh = box[1];

                ProjectionGeometry.Mapping fit = ProjectionGeometry.calculate(
                        sw, sh, 7, 11, bw, bh, ProjectionGeometry.SCALE_FIT);
                assertEquals(0, fit.srcX);
                assertEquals(0, fit.srcY);
                assertEquals(sw, fit.srcW);
                assertEquals(sh, fit.srcH);
                assertCenteredInsideBox(fit, 7, 11, bw, bh);
                assertAspectWithinOnePixel(fit.srcW, fit.srcH, fit.dstW, fit.dstH);

                ProjectionGeometry.Mapping fill = ProjectionGeometry.calculate(
                        sw, sh, 7, 11, bw, bh, ProjectionGeometry.SCALE_FILL);
                assertEquals(0, fill.srcX);
                assertEquals(0, fill.srcY);
                assertEquals(sw, fill.srcW);
                assertEquals(sh, fill.srcH);
                assertEquals(7, fill.dstX);
                assertEquals(11, fill.dstY);
                assertEquals(bw, fill.dstW);
                assertEquals(bh, fill.dstH);

                ProjectionGeometry.Mapping zoom = ProjectionGeometry.calculate(
                        sw, sh, 7, 11, bw, bh, ProjectionGeometry.SCALE_ZOOM);
                assertEquals(7, zoom.dstX);
                assertEquals(11, zoom.dstY);
                assertEquals(bw, zoom.dstW);
                assertEquals(bh, zoom.dstH);
                assertTrue(zoom.srcX >= 0);
                assertTrue(zoom.srcY >= 0);
                assertTrue(zoom.srcW > 0 && zoom.srcX + zoom.srcW <= sw);
                assertTrue(zoom.srcH > 0 && zoom.srcY + zoom.srcH <= sh);
                assertTrue(Math.abs(zoom.srcX - (sw - zoom.srcX - zoom.srcW)) <= 1);
                assertTrue(Math.abs(zoom.srcY - (sh - zoom.srcY - zoom.srcH)) <= 1);
                assertAspectWithinOnePixel(zoom.srcW, zoom.srcH, zoom.dstW, zoom.dstH);
            }
        }
    }

    private static void assertCenteredInsideBox(ProjectionGeometry.Mapping mapping,
                                                int boxX, int boxY, int boxW, int boxH) {
        assertTrue(mapping.dstW > 0 && mapping.dstW <= boxW);
        assertTrue(mapping.dstH > 0 && mapping.dstH <= boxH);
        int left = mapping.dstX - boxX;
        int top = mapping.dstY - boxY;
        int right = boxW - left - mapping.dstW;
        int bottom = boxH - top - mapping.dstH;
        assertTrue(left >= 0 && right >= 0 && Math.abs(left - right) <= 1);
        assertTrue(top >= 0 && bottom >= 0 && Math.abs(top - bottom) <= 1);
    }

    private static void assertAspectWithinOnePixel(int aw, int ah, int bw, int bh) {
        long delta = Math.abs((long) aw * bh - (long) ah * bw);
        long onePixelTolerance = Math.max(Math.max(aw, ah), Math.max(bw, bh));
        assertTrue("aspect delta " + delta + " exceeds " + onePixelTolerance,
                delta <= onePixelTolerance);
    }
}
