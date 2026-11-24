package com.overdrive.app.surveillance;

import static org.junit.Assert.*;

import org.junit.Test;

public class FoveatedCropperSurfaceTextureTest {
    @Test
    public void existingTexturePathsKeepProducerSpaceCropShader() {
        for (boolean texture2D : new boolean[]{false, true}) {
            String shader = FoveatedCropper.buildFragmentShader(texture2D, false);
            assertFalse(shader.contains("uTexMatrix"));
            assertTrue(shader.contains("texture2D(uCameraTex, samplePos)"));
        }
    }

    @Test
    public void optedInShaderTransformsTheSelectedCropBeforeSampling() {
        for (boolean texture2D : new boolean[]{false, true}) {
            String shader = FoveatedCropper.buildFragmentShader(texture2D, true);
            assertTrue(shader.contains("uniform mat4 uTexMatrix;"));
            assertTrue(shader.contains(
                "texture2D(uCameraTex, (uTexMatrix * vec4(samplePos, 0.0, 1.0)).xy)"));
            assertTrue(shader.contains(texture2D
                ? "uniform sampler2D uCameraTex;" : "uniform samplerExternalOES uCameraTex;"));
            assertTrue(shader.indexOf("uCropRect.x +") < shader.indexOf("vec4 src ="));
        }
    }
}
