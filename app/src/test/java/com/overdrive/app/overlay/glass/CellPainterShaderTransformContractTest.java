package com.overdrive.app.overlay.glass;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** Keeps animated radial highlights on the Canvas transform path, not Shader local-matrix updates. */
public class CellPainterShaderTransformContractTest {
    @Test
    public void animatedHaloAndSurfaceUseCanvasTransforms() throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(
                "app/src/main/java/com/overdrive/app/overlay/glass/CellPainter.kt")),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("c.translate(cell.cx, sy + hh / 2f)"));
        assertTrue(source.contains("c.drawCircle(0f, 0f, 1f, live)"));
        assertTrue(source.contains("c.translate(shaderCx, shaderCy)"));
        assertTrue(source.contains("c.drawOval("));
        assertFalse(source.contains("cell.haloShader?.setLocalMatrix(m)"));
        assertFalse(source.contains("cell.surfaceShader?.setLocalMatrix(m)"));
    }
}
