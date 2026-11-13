package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiLink5FisheyeModelTest {

    @Test
    public void strengthZeroIsOffAndIdentity() {
        assertEquals(0f, DiLink5FisheyeModel.tanHalfFov(0f), 0f);
        assertEquals(0f, DiLink5FisheyeModel.tanHalfFov(-5f), 0f);
        float[] uv = DiLink5FisheyeModel.sourceUv(0f, 0.13f, 0.87f);
        assertEquals(0.13f, uv[0], 0f);
        assertEquals(0.87f, uv[1], 0f);
    }

    @Test
    public void higherStrengthNarrowsTheView() {
        float mild = DiLink5FisheyeModel.tanHalfFov(1f);
        float mid = DiLink5FisheyeModel.tanHalfFov(50f);
        float strong = DiLink5FisheyeModel.tanHalfFov(100f);
        assertTrue(mild > mid && mid > strong && strong > 0f);
        assertEquals(strong, DiLink5FisheyeModel.tanHalfFov(250f), 0f);
    }

    @Test
    public void tileCentreLooksAtTheFisheyeCentre() {
        // Output centre → sensor centre at tile px (640, 370) from top-left.
        float[] uv = DiLink5FisheyeModel.sourceUv(60f, 0.5f, 0.5f);
        assertEquals(640f / 1920f, uv[0], 1e-5f);
        assertEquals(1f - 370f / 1080f, uv[1], 1e-5f);
    }

    @Test
    public void everyOutputPixelSamplesThePictureNotThePadding() {
        for (int s = 1; s <= 100; s += 11) {
            for (float u = 0f; u <= 1f; u += 0.125f) {
                for (float v = 0f; v <= 1f; v += 0.125f) {
                    float[] src = DiLink5FisheyeModel.sourceUv(s, u, v);
                    // picture = left 2/3 of the tile, top 850 of 1080 rows
                    assertTrue("x in padding s=" + s, src[0] <= 1280f / 1920f + 1e-4f);
                    assertTrue("y in padding s=" + s, src[1] >= 1f - 850f / 1080f - 1e-4f);
                    // and inside the fisheye circle (no black ring) along x
                    double dx = (src[0] - 640f / 1920f) * 1920.0;
                    assertTrue(Math.abs(dx) <= 640.0 + 1e-3);
                }
            }
        }
    }

    @Test
    public void mappingIsMonotonicAlongTheAxis() {
        float prev = -1f;
        for (int i = 0; i <= 20; i++) {
            float u = 0.5f + i * 0.025f;
            float su = DiLink5FisheyeModel.sourceUv(50f, u, 0.5f)[0];
            assertTrue(su >= prev);
            prev = su;
        }
    }

    @Test
    public void glslHasNoFormatLeftoversAndDeclaresHelpers() {
        String g = DiLink5FisheyeModel.GLSL;
        assertFalse(g.contains("%"));
        assertTrue(g.contains("uniform float uDl5FishTan;"));
        assertTrue(g.contains("vec2 dl5Unfish(vec2 t)"));
        assertTrue(g.contains("vec2 dl5UnfishMosaic(vec2 uv)"));
    }

    @Test
    public void onlyTheTangOnDiLink5GetsTheUnwarp() {
        assertEquals(40f, DiLink5FisheyeModel.effectiveStrength(true, "tang", "user", 40), 0f);
        assertEquals(40f, DiLink5FisheyeModel.effectiveStrength(true, " Tang ", "auto", 40), 0f);
        // Other DiLink 5 cars use a different sensor geometry.
        assertEquals(0f, DiLink5FisheyeModel.effectiveStrength(true, "sealion7", "user", 40), 0f);
        // The renderer's cosmetic fallback is not the owner's choice.
        assertEquals(0f, DiLink5FisheyeModel.effectiveStrength(true, "tang", "unset", 40), 0f);
        assertEquals(0f, DiLink5FisheyeModel.effectiveStrength(true, "", "user", 40), 0f);
        // Not DiLink 5: never.
        assertEquals(0f, DiLink5FisheyeModel.effectiveStrength(false, "tang", "user", 40), 0f);
    }

    @Test
    public void ownSettingStartsOffAndIsClamped() {
        assertEquals(0f, DiLink5FisheyeModel.effectiveStrength(true, "tang", "user", 0), 0f);
        assertEquals(0f, DiLink5FisheyeModel.effectiveStrength(true, "tang", "user", -3), 0f);
        assertEquals(100f, DiLink5FisheyeModel.effectiveStrength(true, "tang", "user", 250), 0f);
        assertEquals("dilink5FisheyeStrength", DiLink5FisheyeModel.CONFIG_KEY);
    }
}
